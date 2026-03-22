(ns falcon.nav
  (:require [etaoin.api :as e]
            [falcon.core :as core]))

(defn- count-elements
  "Count how many elements currently match a query."
  [driver q]
  (count (e/query-all driver q)))

(defn- done?
  "Check if the 'end of content' sentinel elemeent exists."
  [driver done-el]
  (when done-el
    (e/exists? driver done-el)))

(defn click [{:keys [driver site]} path opts]
  (let [leaf (core/resolve-intent site path)]
    (println leaf)
    (e/click driver (:q leaf))))

(defn scroll-until!
  "Scroll the page according to the site's :nav :scroll config
  For :infinite strategy: scrolls to bottom, waits for new content to load,
  repeats until either :done-el appears, :max-scrolls is hit, or no new
  content loads after a scroll. Returns the driver."
  [driver {:keys [nav] :as _site}]
  (let [{:keys [strategy wait-el done-el max-scrolls pause-ms]
         :or {max-scrolls 50 pause-ms 1500}} (:scroll nav)]
    (case strategy
      :infinite
      (loop [n 0
             prev-count (count-elements driver wait-el)]
        (when (and (< n max-scrolls)
                   (not (done? driver done-el)))
          (e/scroll-bottom driver)
          (e/wait driver (/ pause-ms 1000.0))
          (let [new-count (count-elements driver wait-el)]
            (if (> new-count prev-count)
              (recur (inc n) new-count)
              driver))))
      :none driver
    ;; default / unknown
      (throw (ex-info "Unknown nav strategy" {:strategy strategy})))
    driver))

(defn scroll-n!
  "Scroll to bottom n times with a pause between each. Useful for
  quick ad-hoc scrolling at the REPL when you don't want config-driven behavior."
  [driver n pause-ms]
  (dotimes [_ n]
    (e/scroll-bottom driver)
    (e/wait driver (/ pause-ms 1000.0)))
  driver)

;; In the site config:
;; :nav {:scroll {:strategy :paginate
;; :paginate
;; (loop [n 0]
;; (when (and (< n max-pages) (not (done? driver done-el)))
;; (e/click driver next-btn)
;; (e/wait-visible driver wait-el)
;; (recur (inc n))))
