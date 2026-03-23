(ns falcon.nav-test
  (:require [falcon.core :as f]
            [falcon.nav :as nav]
            [clojure.test :refer [deftest is testing]]))

;; ---- Introspection tests ----

(deftest verbs-test
  (testing "verbs returns a sorted vector including :scroll"
    (let [site (f/load-site :test-site)
          vs   (nav/verbs site)]
      (is (vector? vs))
      (is (= vs (sort vs)))
      (is (some #{:scroll} vs)))))

(deftest targets-test
  (testing "targets returns a sorted vector including :infinite for :scroll"
    (let [site (f/load-site :test-site)
          ts   (nav/targets site :scroll)]
      (is (vector? ts))
      (is (= ts (sort ts)))
      (is (some #{:infinite} ts)))))

(deftest describe-test
  (testing "describe returns verb, target, bindings, and opts"
    (let [site (f/load-site :test-site)
          desc (nav/describe site [:scroll :infinite])]
      (is (map? desc))
      (is (= :scroll (:verb desc)))
      (is (= :infinite (:target desc)))
      (is (contains? desc :bindings))
      (is (contains? desc :opts))
      (is (contains? (:bindings desc) :wait-el)))))

;; ---- EDN shape tests ----

(deftest nav-structure-test
  (testing "nav section is a map with keyword verb keys and no behavioral params"
    (doseq [site-key [:test-site :public-test-site]]
      (let [site (f/load-site site-key)
            nav-map (:nav site)]
        (is (map? nav-map)
            (str site-key " :nav should be a map"))
        (is (every? keyword? (keys nav-map))
            (str site-key " verb keys should be keywords"))
        (is (nil? (get-in site [:nav :pause-ms]))
            (str site-key " :pause-ms should not be in :nav"))
        (is (nil? (get-in site [:nav :max-scrolls]))
            (str site-key " :max-scrolls should not be in :nav"))
        (is (nil? (get-in site [:nav :scroll :pause-ms]))
            (str site-key " :pause-ms should not be in :nav :scroll"))
        (is (nil? (get-in site [:nav :scroll :max-scrolls]))
            (str site-key " :max-scrolls should not be in :nav :scroll"))))))
