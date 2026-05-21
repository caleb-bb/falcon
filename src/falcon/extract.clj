(ns falcon.extract
  (:require [etaoin.api :as e]
            [clj-http.client :as http]
            [falcon.core :as core]
            [clojure.string :as str]))

;; ---- API ----

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
