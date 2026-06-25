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

(defn- goto-recipe!
  "Navigate by URL rather than by clicking a DOM element. Use for SPA tabs
   and other targets that are route-driven instead of plain <a> links.
   The leaf may carry an absolute :url, or a :path appended to base-url."
  [driver base-url leaf]
  (e/go driver (or (:url leaf) (str base-url (:path leaf))))
  driver)

(defn- scroll-recipe!
  [driver leaf opts _args]
  (let [wait-q    (get-in leaf [:wait-el :q])
        done-el   (:done-el leaf)
        max-scrolls (get opts :max-scrolls 50)
        pause-ms    (get opts :pause-ms 1500)
        ;; How many consecutive no-growth scrolls to tolerate before
        ;; concluding the feed is exhausted. Infinite feeds load
        ;; asynchronously, so a single slow load looks like the end —
        ;; without patience the loop bails on the first stall.
        patience    (get opts :patience 3)]
    (loop [n 0
           stable 0
           prev-count (count-elements driver wait-q)]
      (if (or (>= n max-scrolls)
              (done? driver done-el)
              (>= stable patience))
        driver
        (do
          (e/scroll-bottom driver)
          (e/wait driver (/ pause-ms 1000.0))
          (let [new-count (count-elements driver wait-q)]
            (if (> new-count prev-count)
              (recur (inc n) 0 new-count)
              (recur (inc n) (inc stable) prev-count))))))))

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

(defn- resolve-leaf
  "Walk the site's :nav tree by path, returning the leaf binding.
   Throws with the available keys at the point of failure rather than
   returning nil — a missing leaf otherwise surfaces downstream as a
   confusing empty-URL / nil-query webdriver error."
  [site path]
  (loop [node (:nav site)
         remaining path
         walked [:nav]]
    (cond
      (nil? node)
      (throw (ex-info
              (str "Site \"" (:name site) "\" has no nav binding at "
                   (conj walked (first remaining))
                   " — \"" (name (first remaining)) "\" lookup hit a nil parent.")
              {:site (:name site) :path (conj walked (first remaining))}))

      (empty? remaining)
      node

      :else
      (let [k (first remaining)
            next-node (get node k)]
        (if (nil? next-node)
          (throw (ex-info
                  (str "Site \"" (:name site) "\" has no nav binding at "
                       (conj walked k) ". Available: " (vec (keys node)))
                  {:site (:name site)
                   :path (conj walked k)
                   :available (vec (keys node))}))
          (recur next-node (rest remaining) (conj walked k)))))))

(defn do!
  "Execute a navigation action described by path against the site config.
   The first element of path is the verb (:click, :goto, :scroll, :search, :paginate).
   Remaining elements walk the intent tree to the DOM bindings.
   Additional args are verb-specific (e.g. search text for :search).
   Throws a helpful error if path resolves to no leaf.
   Returns the driver."
  [driver site path & args]
  (let [verb (first path)
        leaf (resolve-leaf site path)
        opts (get-in site [:opts verb])]
    (case verb
      :click    (click-recipe! driver leaf opts args)
      :goto     (goto-recipe! driver (:base-url site) leaf)
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
