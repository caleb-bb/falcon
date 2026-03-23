(ns falcon.nav
  (:require [etaoin.api :as e]))

;; ---- Private helpers ----

(defn- count-elements
  "Count how many elements currently match a query."
  [driver q]
  (count (e/query-all driver q)))

(defn- done?
  "Check if the 'end of content' sentinel element exists."
  [driver done-el]
  (when done-el
    (e/exists? driver (:q done-el))))

;; ---- Private recipe functions ----

(defn- click-recipe!
  [driver leaf _opts _args]
  (e/click driver (:q leaf))
  driver)

(defn- scroll-recipe!
  [driver leaf opts _args]
  (let [wait-q    (get-in leaf [:wait-el :q])
        done-el   (:done-el leaf)
        max-scrolls (get opts :max-scrolls 50)
        pause-ms    (get opts :pause-ms 1500)]
    (loop [n 0
           prev-count (count-elements driver wait-q)]
      (if (or (>= n max-scrolls)
              (done? driver done-el))
        driver
        (do
          (e/scroll-bottom driver)
          (e/wait driver (/ pause-ms 1000.0))
          (let [new-count (count-elements driver wait-q)]
            (if (> new-count prev-count)
              (recur (inc n) new-count)
              driver)))))))

(defn- search-recipe!
  [driver leaf _opts args]
  (e/fill driver (:q leaf) (first args))
  (e/click driver (get-in leaf [:params :submit :q]))
  driver)

(defn- paginate-recipe!
  [driver leaf opts _args]
  (let [next-q   (get-in leaf [:next-btn :q])
        wait-q   (get-in leaf [:wait-el :q])
        done-el  (:done-el leaf)
        max-pages (get opts :max-pages 20)
        pause-ms  (get opts :pause-ms 1500)]
    (loop [n 0]
      (if (or (>= n max-pages)
              (done? driver done-el))
        driver
        (do
          (e/click driver next-q)
          (e/wait driver (/ pause-ms 1000.0))
          (e/wait-visible driver wait-q)
          (recur (inc n)))))))

;; ---- Public effectful functions ----

(defn do!
  "Execute a navigation action described by path against the site config.
   The first element of path is the verb (:click, :scroll, :search, :paginate).
   Remaining elements walk the intent tree to the DOM bindings.
   Additional args are verb-specific (e.g. search text for :search).
   Returns the driver."
  [driver site path & args]
  (let [verb (first path)
        leaf (get-in site (into [:nav] path))
        opts (get-in site [:opts verb])]
    (case verb
      :click    (click-recipe! driver leaf opts args)
      :scroll   (scroll-recipe! driver leaf opts args)
      :search   (search-recipe! driver leaf opts args)
      :paginate (paginate-recipe! driver leaf opts args)
      (throw (ex-info (str "Unknown nav verb: " verb) {:verb verb})))
    driver))

(defn scroll-n!
  "Scroll to bottom n times with a pause between each. Useful for
  quick ad-hoc scrolling at the REPL when you don't want config-driven behavior."
  [driver n pause-ms]
  (dotimes [_ n]
    (e/scroll-bottom driver)
    (e/wait driver (/ pause-ms 1000.0)))
  driver)

;; ---- Public pure introspection functions ----

(defn verbs
  "Return a sorted vector of nav verb keywords available in site."
  [site]
  (-> site :nav keys sort vec))

(defn targets
  "Return a sorted vector of target keywords under verb in site's :nav."
  [site verb]
  (-> (get-in site [:nav verb]) keys sort vec))

(defn describe
  "Describe a nav action: its verb, target, DOM bindings, and effective opts."
  [site path]
  (let [verb   (first path)
        target (second path)]
    {:verb     verb
     :target   target
     :bindings (get-in site (into [:nav] path))
     :opts     (get-in site [:opts verb])}))

(defn tree
  "Pretty-print the full nav registry to stdout.
   Shows each verb, its targets, and their leaf shapes. Returns nil."
  [site]
  (doseq [[verb targets-map] (sort (:nav site))]
    (println (str "  " (name verb)))
    (doseq [[target leaf] (sort targets-map)]
      (println (str "    " (name target) " => " (pr-str leaf)))))
  nil)
