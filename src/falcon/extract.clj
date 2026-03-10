(ns falcon.extract
  (:require [etaoin.api :as e]
            [clj-http.client :as http]))

;; ---- Web scraping verbs ----

(defn- extract-field
  "Extract a single field value from a parent element.
  attr can be :text for inner text, or any HTML attribute keyword, such as :href"
  [driver parent-el {:keys [q attr]}]
  (try
    (let [el (e/query-from driver parent-el q)]
      (case attr
        :text (e/get-element-text-el driver el)
        (e/get-element-attr-el driver el (name attr))))
    (catch Exception _e nil)))

(defn extract-all
  "Extract structured data from the page using the site config's :extract map.
  Returns a vector of maps, one per container element found."
  [driver {:keys [extract] :as _site}]
  (let [{:keys [container fields]} extract
        containers (e/query-all driver container)]
    (mapv (fn [container-el]
            (reduce-kv
             (fn [acc field-name field-spec]
               (assoc acc field-name (extract-field driver container-el field-spec)))
             {}
             fields))
          containers)))

(defn extract-raw
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
      (spit (str "/resources/html/" filename ".html"))))
