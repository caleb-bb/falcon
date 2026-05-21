(ns falcon.extract-test
  (:require [falcon.extract :as x]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;; ---- Helpers ----

(defn- with-temp-dir
  "Create a temp directory, run func with its path (trailing /), delete on exit."
  [func]
  (let [dir (io/file (str "target/test-tmp/" (random-uuid) "/"))]
    (.mkdirs dir)
    (try
      (func (str dir "/"))
      (finally
        (run! io/delete-file (reverse (file-seq dir)))))))

(defn- fake-response [status body]
  {:status status :body (.getBytes body) :headers {}})

;; ---- save-one ----

(deftest save-one-success-test
  (testing "200 response saves body to [hash].ext and returns the path"
    (with-temp-dir
      (fn [dir]
        (with-redefs [http/get (fn [_ _] (fake-response 200 "hello world"))]
          (let [path (x/save-one "html" "https://example.com/page" dir)]
            (is (string? path))
            (is (.endsWith path ".html"))
            (is (.exists (io/file path)))
            (is (= "hello world" (slurp path)))))))))

(deftest save-one-deterministic-url-test
  (testing "same URL produces the same filename"
    (with-temp-dir
      (fn [dir]
        (with-redefs [http/get (fn [_ _] (fake-response 200 "content"))]
          (let [path1 (x/save-one "txt" "https://example.com/page" dir)
                path2 (x/save-one "txt" "https://example.com/page" dir)]
            (is (= path1 path2))))))))

(deftest save-one-different-url-different-filename-test
  (testing "different URLs produce different filenames"
    (with-temp-dir
      (fn [dir]
        (with-redefs [http/get (fn [url _]
                                 (fake-response 200 (str "body for " url)))]
          (let [path1 (x/save-one "txt" "https://a.com" dir)
                path2 (x/save-one "txt" "https://b.com" dir)]
            (is (not= path1 path2))))))))

(deftest save-one-dedup-test
  (testing "second save of same URL reuses existing file without overwriting"
    (with-temp-dir
      (fn [dir]
        (with-redefs [http/get (fn [_ _] (fake-response 200 "original"))]
          (let [path1 (x/save-one "html" "https://example.com/page" dir)
                _     (spit path1 "tampered")
                path2 (x/save-one "html" "https://example.com/page" dir)]
            (is (= path1 path2))
            (is (= "tampered" (slurp path2))
                "should not have overwritten the existing file")))))))

(deftest save-one-non-2xx-returns-response-test
  (testing "non-2xx status returns the response map, writes nothing"
    (with-temp-dir
      (fn [dir]
        (with-redefs [http/get (fn [_ _] (fake-response 404 "not found"))]
          (let [result (x/save-one "html" "https://example.com/gone" dir)]
            (is (map? result))
            (is (= 404 (:status result)))
            (is (empty? (.list (io/file dir))))))))))

(deftest save-one-creates-parent-dirs-test
  (testing "nested directory path is created automatically"
    (with-temp-dir
      (fn [dir]
        (let [nested (str dir "deep/nested/")]
          (with-redefs [http/get (fn [_ _] (fake-response 200 "deep"))]
            (let [path (x/save-one "txt" "https://example.com" nested)]
              (is (.exists (io/file path))))))))))
