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



(defn save-one
  "GET a URL, save the body as [hash].[ext], and write a companion .edn metadata file.
  Returns the metadata map on success, or the response map on failure."
  [ext url]
  (let [resp (http/get url {:throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (let [body      (:body resp)
            hash      (-> body .getBytes
                          (java.security.MessageDigest/getInstance "SHA-256")
                          (.digest)
                          (->> (map #(format "%02x" %))
                               (apply str)))
            filename  (str hash "." ext)
            file-path (str "resources/html/" filename)
            _         (spit file-path body)
            metadata  {:url       url
                       :hash      hash
                       :file-path file-path
                       :format    ext
                       :file-size (count body)}]
        (spit (str "resources/html/" hash ".edn") (pr-str metadata))
        metadata)
      resp)))

(defn save-many
  "As save-one, but takes a list of urls and assumes all have the same extension"
  [urls ext]
  (mapv #(save-one ext %) urls))
