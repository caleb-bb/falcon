(ns falcon.extract
  (:require [etaoin.api :as e]
            [clj-http.client :as http]
            [falcon.core :as core]))

;; ---- API ----

(defn all-inner-text
  "Extract inner text of all matching elements to a vector"
  [session intent-path]
  (let [driver (:driver session)
        site   (:site session)
        leaf   (core/resolve-intent (get-in site [:extract]) intent-path)]
    (core/leaf-map driver leaf e/get-element-text-el)))

(defn raw
  "Ad-hoc extraction helper for the REPL. Takes a CSS/XPath query and
  returns the text content of all matching elements as a vector of strings."
  [driver q]
  (mapv #(e/get-element-text-el driver %) (e/query-all driver q)))

;; ---- Functions closer to raw http requests ----

(defn body
  "Retrieves the body of an http request"
  [url]
  (-> url
      (http/get)
      (get :body)))

;; ---- File i/o (primarily for convenience at this point) ----

(defn save-site
  "Retrieves html from a site and saves it to the site directory"
  [url filename]
  (-> url
      (body)
      (spit (str "resources/html/" filename ".html"))))
