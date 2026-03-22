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

; ---- Constants ----

(def valid-leaves
  [{:q {:css ".answer-text"} :attr :text}
   {:q {:css "a.profile-link"} :attr :href}
   {:q {:css "button.load-more"}}
   {:q {:xpath "//div[@class='bio']//p[1]"} :attr :text}
   {:q {:tag :input} :attr :value}
   {:q {:id "search-input"}}
   {:q {:css "input[name=email]"} :params {:value :env/USERNAME}}
   {:q {:css "#search-input"} :params {:submit {:q {:css "button.search-go"}}}}
   {:q {:name "avatar"} :attr :value}
   {:q {:css ".rich-text-body"} :attr :inner-html}])

(def invalid-leaves
  [{:q {:css ".title"} :attrr :text}
   {:attr :text :params {:value "hello"}}
   {:q ".title" :attr :text}
   {:q {:css ".title" :xpath "//div"} :attr :text}
   {:q {:bogus "not a real locator"}}
   {:q {:css "   "} :attr :text}
   {:q {:css ".title"} :attr :flurbnax}
   {:q {:css ".title"} :params "not a map"}])

;; ---- resolve-env ----

;; This will become property-based later.
(deftest resolve-env-passthrough-test
  (testing "config with no :env/ keywords passes through unchanged"
    (let [config {:name "Test"
                  :base-url "https://example.com"
                  :nav {:scroll {:strategy :infinite
                                 :pause-ms 1000}}}]
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
    (let [config {:strategy :nav/infinite
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
      (is (= :infinite (get-in site [:nav :scroll :strategy]))))))

(deftest load-site-public-no-env-test
  (testing "public fixture loads with no :env/ keywords"
    (let [site (f/load-site :public-test-site)]
      (is (= "Public Test Site" (:name site)))
      (is (string? (:base-url site))))))

(deftest load-site-missing-throws-test
  (testing "Loading a nonexistent site throws"
    (is (thrown? Exception (f/load-site :no-such-site-ever)))))

;; --- config shape validation ----

(deftest valid-test-site-passes-validation
  (testing "pipeline validates legal site edn"
    (let [site (f/load-site :test-site)]
      (is (= (f/valid-edn? site) true)))))

(deftest illegal-keys-returns-false
  (testing "pipeline invalidates edn with illegal keys"
    (let [site (f/load-site :invalid-site-with-illegal-keys)]
      (is (= (f/valid-edn? site) false)))))

(deftest no-name-returns-false
  (testing "sites without a name key are invalid"
    (let [site (f/load-site :invalid-site-with-no-name)]
      (is (= (f/valid-edn? site) false)))))

(deftest illegal-name-returns-false
  (testing "sites with non-string name key are invalid"
    (let [site (f/load-site :invalid-site-with-illegal-name)]
      (is (= (f/valid-edn? site) false)))))

(deftest illegal-base-url-returns-false
  (testing "sites with non-string non-env base-url are invalid"
    (let [site (f/load-site :invalid-site-with-illegal-base-url)]
      (is (= (f/valid-edn? site) false)))))

(deftest no-name-returns-false
  (testing "sites without an opts key are invalid"
    (let [site (f/load-site :invalid-site-with-no-opts)]
      (is (= (f/valid-edn? site) false)))))

(deftest illegal-opts-returns-false
  (testing "sites with non-map opts key are invalid"
    (let [site (f/load-site :invalid-site-with-illegal-opts)]
      (is (= (f/valid-edn? site) false)))))

(deftest illegal-auth-returns-false
  (testing "sites with non-map auth key are invalid"
    (let [site (f/load-site :invalid-site-with-illegal-auth)]
      (is (= (f/valid-edn? site) false)))))

(deftest illegal-extract-returns-false
  (testing "sites with non-map extract key are invalid"
    (let [site (f/load-site :invalid-site-with-illegal-extract)]
      (is (= (f/valid-edn? site) false)))))

(deftest illegal-nav-returns-false
  (testing "sites with non-map nav key are invalid"
    (let [site (f/load-site :invalid-site-with-illegal-nav)]
      (is (= (f/valid-edn? site) false)))))

(deftest valid-leaves-return-true
  (testing "leaves with correct structure are valid"
    (every? f/valid-leaf? valid-leaves)))

(deftest invalid-leaves-return-false
  (testing "leaves with incorrect structure are invalid"
    (every? false? (map f/valid-leaf? invalid-leaves))))
