(ns falcon.extract
  (:require [etaoin.api :as e]))

(defn- extract-field
  "Extract a single  field value from a parent element.
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
  [driver {:keys [container fields]} extract
   containers (e/query-all driver container)]
  (mapv (fn [container-el]
          (reduce-kv
           (fn [acc field-name field-spec]
             (assoc acc field-name (extract-field driver container-el field-spec)))
           {}
           fields))
        containers))


(defn extract-raw
  "Ad-hoc extraction helper for the REPL. Taks a CSS/XPath query and
  reuturns the text content of all matching elements as a vector of strings."
  [driver q]
  (mapv #(e/get-element-text-el driver %) (e/query-all driver q)))
