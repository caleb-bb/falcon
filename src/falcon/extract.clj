(ns falcon.extract
  (:require [etaoin.api :as e]
            [clj-http.client :as http]
            [falcon.core :as core]
            [falcon.nav :as nav]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ---- API ----

(defn- el-property
  "Read the property named by `attr` from a single element. :text (or nil)
  reads the element's text; any other keyword reads that HTML attribute. The
  locator that found the element is irrelevant here — :css, :xpath, etc. all
  yield an element, so this stays agnostic to how membership was decided."
  [driver el attr]
  (if (or (nil? attr) (= :text attr))
    (e/get-element-text-el driver el)
    (e/get-element-attr-el driver el (name attr))))

(defn for-all
  "Set-builder extraction:  { property(x) | x matches noun }.

  `noun` is an intent path into the site's :extract tree (a keyword, or a
  vector of keywords) naming a leaf. The leaf's :q supplies the membership
  predicate — the locator — and the function is agnostic to its kind (:css,
  :xpath, :id, ...). The leaf's :attr names the property pooled (:text by
  default, else an HTML attribute such as :href).

  Every matching element is reduced to its property and the results are
  pooled into a set (deduped, unordered). Returns a set of strings.

  Example: (for-all session [:answer :fields :url])  ;; #{ href, href, ... }"
  [session noun]
  (let [driver (:driver session)
        site   (:site session)
        path   (if (sequential? noun) noun [noun])
        leaf   (core/resolve-intent (get-in site [:extract]) path)
        attr   (:attr leaf)]
    (into #{} (core/leaf-map driver leaf #(el-property %1 %2 attr)))))

(defn all-inner-text
  "Extract inner text of all matching elements to a vector"
  [session intent-path]
  (let [driver (:driver session)
        site   (:site session)
        leaf   (core/resolve-intent (get-in site [:extract]) intent-path)]
    (core/leaf-map driver leaf #(e/get-element-text-el))))

(defn all-attr
  "Extract an attribute from all matching elements to a vector"
  [session intent-path attr]
  (let [driver (:driver session)
        site   (:site session)
        leaf   (core/resolve-intent (get-in site [:extract]) intent-path)]
    (core/leaf-map driver leaf (fn [driver el] (e/get-element-attr-el driver el (name attr))))))

(defn all-links
  "Extract display text and href from all matching link elements.
  Returns a vector of {:text ... :href ...} maps."
  [session intent-path]
  (let [driver (:driver session)
        site   (:site session)
        leaf   (core/resolve-intent (get-in site [:extract]) intent-path)]
    (core/leaf-map driver leaf
                   (fn [driver el]
                     {:text (e/get-element-text-el driver el)
                      :href (e/get-element-attr-el driver el "href")}))))

(defn raw
  "Ad-hoc extraction helper for the REPL. Takes a CSS/XPath query and
  returns the text content of all matching elements as a vector of strings."
  [driver q]
  (mapv #(e/get-element-text-el driver %) (e/query-all driver q)))

(defn- field-value
  "Read one field leaf scoped *within* a container element. Returns nil when
  the field isn't present in this container (some cards in a feed carry only a
  subset of the fields). Membership is decided by the field leaf's :q relative
  to the container; the property pooled is its :attr (:text by default, else an
  HTML attribute such as :href)."
  [driver container-el {:keys [q attr]}]
  (when-let [el (first (e/children driver container-el q))]
    (el-property driver el attr)))

(defn records
  "Container-aware extraction:  one map per container, not one set per field.

  `noun` is an intent path into the site's :extract tree naming a node that has
  a :container leaf (the per-record wrapper) and a :fields map (field-name ->
  leaf). For every element matching :container, each field leaf is queried
  *scoped to that container* and reduced to its property, yielding a vector of
  maps keyed by field name — so question/url/text stay associated, in document
  order, with no dedup.

  Rows where every field is blank/nil are dropped: a feed often carries a
  header/summary card that matches the record container but none of its fields.

  Example: (records session :answer)
  ;; => [{:question \"...\" :url \"...\" :text \"...\"} ...]"
  [session noun]
  (let [driver (:driver session)
        site   (:site session)
        path   (if (sequential? noun) noun [noun])
        node   (core/resolve-intent (:extract site) path)
        container-q (get-in node [:container :q])
        fields      (:fields node)]
    (->> (e/query-all driver container-q)
         (mapv (fn [container-el]
                 (reduce-kv
                  (fn [acc fname leaf]
                    (assoc acc fname (field-value driver container-el leaf)))
                  {}
                  fields)))
         (filterv (fn [record]
                    (some #(not (str/blank? %)) (vals record)))))))

;; ---- Basic YAGNI-ware for i/o ----

(def default-save-dir "scraped/")

(def retryable-status? #{429 500 502 503 504})

(defn- retry-delay-ms
  [resp attempt]
  (if-let [retry-after (get-in resp [:headers "Retry-After"])]
    (* 1000 (parse-long retry-after))
    (* 1000 (long (Math/pow 2 attempt)))))

(defn- http-get-with-retry
  "GET with retries on transient failures. Returns the first 2xx response,
  or the last failed response after exhausting retries.
  Options are passed through to clj-http."
  [url max-retries opts]
  (loop [attempt 0]
    (let [resp (http/get url (merge {:throw-exceptions false} opts))]
      (cond
        (<= 200 (:status resp) 299)
        resp

        (and (retryable-status? (:status resp))
             (< attempt max-retries))
        (let [delay (retry-delay-ms resp attempt)]
          (println (str "Retry " (inc attempt) "/" max-retries
                        " for " url " (HTTP " (:status resp)
                        "), waiting " delay "ms"))
          (Thread/sleep delay)
          (recur (inc attempt)))

        :else
        (do (println (str "Failed to fetch " url " — HTTP " (:status resp)))
            resp)))))

(defn- hash-bytes
  "SHA-256 hex digest of a byte array."
  [^bytes ba]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest digest ba)
         (map #(format "%02x" %))
         (apply str))))

(defn- ->slug
  "Convert any string to a filesystem-safe slug."
  [s]
  (-> s
      str/trim
      (str/replace #"[^a-zA-Z0-9._-]" "-")
      (str/replace #"-{2,}" "-")
      (str/replace #"^-|-$" "")))

(defn- write-bytes
  "Write a byte array to path, creating parent dirs as needed."
  [path ^bytes ba]
  (clojure.java.io/make-parents path)
  (with-open [out (clojure.java.io/output-stream path)]
    (.write out ba)))

(defn save-one
  "GET a URL, save the body as [name].[ext] file.
  name defaults to a slug derived from the URL.
  Returns the file path on success, or the response map on failure."
  ([ext url] (save-one ext url default-save-dir nil))
  ([ext url directory-path] (save-one ext url directory-path nil))
  ([ext url directory-path name]
   (let [resp (http-get-with-retry url 3 {:as :byte-array})]
     (if (<= 200 (:status resp) 299)
       (let [body           (:body resp)
             slug           (->slug (or name url))
             data-filename  (str slug "." ext)
             data-file-path (str directory-path data-filename)]
         (if (.exists (clojure.java.io/file data-file-path))
           (do (println (str "File for " url " already exists: " data-file-path))
               data-file-path)
           (do (write-bytes data-file-path body)
               data-file-path)))
       resp))))

(defn save-many
  ([urls ext] (save-many urls ext default-save-dir))
  ([urls ext directory-path]
   (mapv #(save-one ext % directory-path) urls)))

(defn save-many-links
  "Save a collection of {:text :href} maps (as returned by all-links).
  Uses the link display text as the filename stem."
  ([links ext] (save-many-links links ext default-save-dir))
  ([links ext directory-path]
   (mapv (fn [{:keys [text href]}]
           (save-one ext href directory-path text))
         links)))

(defn save-one-with-edn
  "GET a URL, save the body as [name].[ext], and write a companion .edn metadata file.
  name defaults to a slug derived from the URL.
  Returns the metadata map on success, or the response map on failure."
  ([ext url] (save-one-with-edn ext url default-save-dir nil))
  ([ext url directory-path] (save-one-with-edn ext url directory-path nil))
  ([ext url directory-path name]
   (let [resp (http-get-with-retry url 3 {:as :byte-array})]
     (if (<= 200 (:status resp) 299)
       (let [body           (:body resp)
             slug           (->slug (or name url))
             data-filename  (str slug "." ext)
             meta-filename  (str slug ".edn")
             data-file-path (str directory-path data-filename)
             meta-file-path (str directory-path meta-filename)
             metadata       {:url       url
                             :hash      (hash-bytes body)
                             :file-path data-file-path
                             :format    ext
                             :file-size (alength body)}]
         (if (.exists (clojure.java.io/file data-file-path))
           (do (println (str "File for " url " already exists: " data-file-path))
               metadata)
           (do (write-bytes data-file-path body)
               (spit meta-file-path (pr-str metadata))
               metadata)))
       resp))))

(defn save-many-with-edns
  ([urls ext] (save-many-with-edns urls ext default-save-dir))
  ([urls ext directory-path]
   (mapv #(save-one-with-edn ext % directory-path) urls)))

(defn save-many-links-with-edns
  "Save a collection of {:text :href} maps with companion .edn metadata files.
  Uses the link display text as the filename stem."
  ([links ext] (save-many-links-with-edns links ext default-save-dir))
  ([links ext directory-path]
   (mapv (fn [{:keys [text href]}]
           (save-one-with-edn ext href directory-path text))
         links)))

(defn dump
  "Deposit a whole collection of records (maps, as returned by `records`) into
  a *single* file as pretty-printed EDN. Unlike save-many, this is one file for
  the lot, not one file per item — no HTTP, just the in-memory data. Creates
  parent dirs. Returns the file path.
  Defaults to scraped/answers.edn."
  ([records] (dump records (str default-save-dir "answers.edn")))
  ([records file-path]
   (clojure.java.io/make-parents file-path)
   (spit file-path (with-out-str (pp/pprint records)))
   file-path))

;; ---- The one-liner ----

(defn harvest!
  "End-to-end, one call: navigate to the answers tab, scroll the infinite feed
  to the end, extract every answer as a structured record, and deposit them all
  in one EDN file. Takes a session (driver + resolved site) as returned by
  falcon.core/session. `noun` defaults to :answer, `file-path` to
  scraped/answers.edn. Returns the file path.

  One-liner:
    (require '[falcon.core :as c] '[falcon.extract :as x])
    (x/harvest! (c/session :example-site))"
  ([session] (harvest! session :answer (str default-save-dir "answers.edn")))
  ([session noun file-path]
   (let [{:keys [driver site]} session]
     (nav/do! driver site [:goto :answers])
     (nav/do! driver site [:scroll :infinite])
     (dump (records session noun) file-path))))
