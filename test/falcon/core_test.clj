(ns falcon.core-test
  (:require [falcon.core :as f]
            [clojure.test :refer [deftest is testing]]))

;; ---- Helpers ----

(defn- with-fake-env
  "Run func with falcon.core/read-env stubbed to look up
  var-name in env-map."
  [env-map func]
  (with-redefs [f/read-env (fn [var-name] (get env-map var-name))]
    (func)))

;; ---- resolve-env ----

;; This will become property-based later.
(deftest resolve-env-passthrough-test
  (testing "config with no :env/ keywords passes through unchanged"
    (let [config {:name "Test"
                  :base-url "https://example.com"
                  :scroll {:strategy :infinite
                           :pause-ms 1000}}]
      (with-fake-env {}
        #(is (= config (f/resolve-env config)))))))

(deftest resolve-env-replaces-env-keywords-test
  (testing "top-level :env/ keywords are replaced with env var values"
    (with-fake-env {"SITE_URL" "https://secret.example.com"}
      #(is (= {:base-url "https://secret.example.com"}
              (f/resolve-env {:base-url :env/SITE_URL}))))))

(deftest resolve-env-strict-throws-on-missing-var-test
  (testing "strict mode throws on missing env var with useful ex-data"
    (with-fake-env {}
      #(let [ex (try
                  (f/resolve-env {:url :env/MISSING_VAR})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
         (is (some? ex) "expected an exception to be thrown")
         (is (= "MISSING_VAR" (:var (ex-data ex))))))))

(deftest resolve-env-lenient-substitutes-nil-test
  (testing "lenient mode substitutes nil for missing vars without throwing"
    (with-fake-env {}
      #(is (= {:url nil :name "kept"}
              (f/resolve-env {:url :env/NOPE :name "kept"}
                             {:strict? false}))))))

(deftest resolve-env-ignores-non-env-namespaced-keywords-test
  (testing "namespaced keywords that aren't :env/* are left alone"
    (let [config {:strategy :scroll/infinite
                  :type :falcon/custom
                  :plain :no-namespace}]
      (with-fake-env {}
        #(is (= config (f/resolve-env config)))))))

(deftest resolve-env-mixed-values-test
  (testing "string values and :env/ refs coexist correctly"
    (with-fake-env {"SECRET" "shhh"}
      #(is (= {:public "https://example.com"
               :private "shhh"
               :nested {:also-public "visible"
                        :also-private "shhh"}}
              (f/resolve-env {:public "https://example.com"
                              :private :env/SECRET
                              :nested {:also-public "visible"
                                       :also-private :env/SECRET}}))))))

;; ---- load-site ----

(deftest load-site-roundtrip-test
  (testing "test fixture EDN loads and has expected shape"
    (let [site (f/load-site :test-site)]
      (is (= "Test Site" (:name site)))
      (is (= :env/TEST_LOGIN_URL (get-in site [:auth :login-url]))
          "env keywords should be unresolved after load-site")
      (is (= :infinite (get-in site [:scroll :strategy]))))))

(deftest load-site-public-no-env-test
  (testing "public fixture loads with no :env/ keywords"
    (let [site (f/load-site :public-test-site)]
      (is (= "Public Test Site" (:name site)))
      (is (string? (:base-url site))))))

(deftest load-site-missing-throws-test
  (testing "Loading a nonexistent site throws"
    (is (thrown? Exception (f/load-site :no-such-site-ever)))))

;; --- config shape validation ----

(defn valid-site-config?
  "Minimal shape check: does this config have the fields Falcon expects?"
  [config]
  (and (string? (:name config))
       (or (string? (:base-url config))
           (keyword? (:base-url config)))))

(deftest test-fixtures-have-valid-shape-test
  (testing "all test fixture configs pass shape validation"
    (doseq [site-key [:test-site :public-test-site]]
      (let [config (f/load-site site-key)]
        (is (valid-site-config? config)
            (str (name site-key) " failed shape validation"))))))
