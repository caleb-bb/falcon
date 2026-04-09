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

(defn raw
  "Ad-hoc extraction helper for the REPL. Takes a CSS/XPath query and
  returns the text content of all matching elements as a vector of strings."
  [driver q]
  (mapv #(e/get-element-text-el driver %) (e/query-all driver q)))

;; ---- Basic YAGNI-ware for i/o ----

(def default-save-dir "scraped/")

(defn save-one
  "GET a URL, save the body as [hash].[ext] file.
  Returns the file path on success, or the response map on failure."
  ([ext url] (save-one ext url default-save-dir))
  ([ext url directory-path]
   (let [resp (http/get url {:throw-exceptions false})]
     (if (<= 200 (:status resp) 299)
       (let [body           (:body resp)
             digest         (java.security.MessageDigest/getInstance "SHA-256")
             hash           (->> (.digest digest (.getBytes body))
                                 (map #(format "%02x" %))
                                 (apply str))
             data-filename  (str hash "." ext)
             data-file-path (str directory-path data-filename)]
         (if (.exists (clojure.java.io/file data-file-path))
           (do (println (str "File with hash " hash " already exists: " data-file-path))
               data-file-path)
           (do (clojure.java.io/make-parents data-file-path)
               (spit data-file-path body)
               data-file-path)))
       resp))))

(defn save-many
  "As save-one, but takes a list of urls and assumes all have the same extension."
  ([urls ext] (save-many urls ext default-save-dir))
  ([urls ext directory-path]
   (mapv #(save-one ext % directory-path) urls)))

(defn save-one-with-edn
  "GET a URL, save the body as [hash].[ext], and write a companion .edn metadata file.
  Returns the metadata map on success, or the response map on failure."
  ([ext url] (save-one-with-edn ext url default-save-dir))
  ([ext url directory-path]
   (let [resp (http/get url {:throw-exceptions false})]
     (if (<= 200 (:status resp) 299)
       (let [body           (:body resp)
             digest         (java.security.MessageDigest/getInstance "SHA-256")
             hash           (->> (.digest digest (.getBytes body))
                                 (map #(format "%02x" %))
                                 (apply str))
             data-filename  (str hash "." ext)
             meta-filename  (str hash ".edn")
             data-file-path (str directory-path data-filename)
             meta-file-path (str directory-path meta-filename)
             metadata       {:url       url
                             :hash      hash
                             :file-path data-file-path
                             :format    ext
                             :file-size (count body)}]
         (if (.exists (clojure.java.io/file data-file-path))
           (do (println (str "File with hash " hash " already exists: " data-file-path))
               metadata)
           (do (clojure.java.io/make-parents data-file-path)
               (spit data-file-path body)
               (spit meta-file-path (pr-str metadata))
               metadata)))
       resp))))

(defn save-many-with-edns
  "As save-one-with-edn, but takes a list of urls and assumes all have the same extension."
  ([urls ext] (save-many-with-edns urls ext default-save-dir))
  ([urls ext directory-path]
   (mapv #(save-one-with-edn ext % directory-path) urls)))
