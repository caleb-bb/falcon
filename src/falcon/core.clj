(ns falcon.core
  (:require [etaoin.api :as e]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.set :as set]))

;; ---- Config loading ----

(defn read-env
  "Read an environment variable by name. Extracted so tests can
  redef this instead of fighting System/getenv."
  [var-name]
  (System/getenv var-name))

(defn load-site
  "Load a website edn from the sites/directory by keyword name.
  Returns the raw config map with :env/ keywords unresolved.
  Example: (load-site :example-site)"
  [site-key]
  (let [filename (str "sites/" (str/lower-case (name site-key)) ".edn")]
      (-> filename
           io/resource
           slurp
           edn/read-string)))

(defn resolve-env
  "Walk a site config and replace any :env/VAR_NAME values with the
  corresponding environment variable. In strict mode (default), throws
  if a referenced var is missing. In lenient mode, prints a warning and
  substitutes nil."
  ([config] (resolve-env config {:strict? true}))
  ([config {:keys [strict?] :or {strict? true}}]
   (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= "env" (namespace x)))
        (let [var-name (name x)
              value (read-env var-name)]
          (cond
            value value
            strict? (throw (ex-info (str "Missing env var: " var-name) {:var var-name}))
            :else (do (println (str "WARNING: env var " var-name " not set"))
                      nil)))
        x))
    config)))

;; ---- WebDriver checks ----

(def driver-binaries
  {:chrome "chromedriver"
   :firefox "geckodriver"
   :safari "safaridriver"
   :edge "msedgedriver"})

(defn- which
  "Check if a binary is available on PATH. Returns PATH as string or nil."
  [binary]
  (try
    (let [result (shell/sh "which" binary)]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

(defn check-drivers!
  "Print status of known WebDriver binaries. Call at REPL startup to see what's available before starting a session."
  []
  (doseq [[browser binary] driver-binaries]
    (if-let [path (which binary)]
      (println (str " ✓ " (name browser) " binary at " path))
      (println (str " ✗ " (name browser) " binary \"" binary "\" not on PATH")))))

(defn- assert-driver-available!
  "Throw with a helpful message if the requested browser's WebDriver
  binary is not found on PATH."
  [browser]
  (let [binary (get driver-binaries browser)]
    (when (and binary (not (which binary)))
      (throw (ex-info (str "WebDriver not found: " binary
                           ". Install it (e.g. brew install " binary ")")
                      {:browser browser :binary binary})))))

;; ---- Browser lifecycle ----

(def ^:dynamic *default-browser* :chrome)

(defn start
  "Start a browser driver. Browser type defaults to :chrome.
  Options are passed through to etaoin. Headless by default;
  pass {:headless false} to watch.
  Returns the browser."
  ([] (start *default-browser* {}))
  ([browser] (start browser {}))
  ([browser opts]
   (assert-driver-available! browser)
   (let [launch-fn (case browser
                     :chrome e/chrome
                     :firefox e/firefox
                     :safari e/safari
                     :edge e/edge
                     (throw (ex-info (str "Unknown browser: " browser)
                                     {:browser browser})))]
     (launch-fn (merge {:headless true} opts)))))

(defn stop
  "Quit the driver, closing the browser."
  [driver]
  (e/quit driver))

(defn resolve-intent
  "Walk a site config by intent path.
   Returns the leaf node (DOM binding) at the end of the path.
   Throws with helpful error if any key in the path doesn't exist."
  [site path]
  (loop [node site
         remaining path
         walked []]
    (if (empty? remaining)
      node
      (let [k (first remaining)
            next-node (get node k)]
        (if (nil? next-node)
          (throw (ex-info
                   (str "Site \"" (:name site) "\" has no "
                        (name k) " at path " (conj walked k)
                        ". Available: " (vec (keys node)))
                   {:site (:name site)
                    :path (conj walked k)
                    :available (vec (keys node))}))
          (recur next-node (rest remaining) (conj walked k)))))))


;; ---- Convenience: full session ----

(defn session
  "Load a site config, resolve env vars, start a browser, navigate to
  the site's :base-url, and return both driver and config.
  Browser defaults to *default-browser* defaults to :chrome.
  Pass {:strict? false} in env-opt to allow missing env vars (useful
  for messing with config).
  REPL usage:
      (def s (session :example-site))
      (:driver s) ;; the etaoin driver
      (:site s)   ;; the resolved config map"
  ([site-key] (session site-key {}))
  ([site-key opts]
   (let [{:keys [browser headless strict?]
          :or {browser :chrome headless true strict? true}} opts
         site (-> site-key load-site (resolve-env {:strict? strict?}))
         driver (start browser {:headless headless})]
     (when-let [url (:base-url site)]
       (e/go driver url))
     {:driver driver :site site})))

(defn see-inner [driver leaf]
  (->> (e/query-all driver (:q leaf))
       (mapv #(e/get-element-inner-html-el driver %))))

;; ---- Validators ----

(def legal-keys #{:name :base-url :auth :nav :extract :opts})
(def required-keys #{:base-url :name :opts})
(def verbs #{:auth :nav :extract})

(def locator-types #{:css :xpath :tag :fn :id})
(def supported-attrs #{:text :href :src :alt :value :title :class :id :inner-html :outer-html})
(def legal-leaf-keys #{:q :attr :params})

(defn valid-leaf? [{:keys [q attr params] :as leaf}]
  (and (contains? leaf :q)
       (set/subset? (set (keys leaf)) legal-leaf-keys)
       (map? q)
       (= 1 (count q))
       (let [[loc-type loc-val] (first q)]
         (and (contains? locator-types loc-type)
              (if (= :css loc-type)
                (and (string? loc-val)
                     (not (str/blank? loc-val)))
                true)))
       (or (nil? attr) (contains? supported-attrs attr))
       (or (nil? params) (map? params))))

(defn legal-keys? [edn]
  (let [edn-keys (set (keys edn))]
    (set/subset? edn-keys legal-keys)))

(defn has-a-verb? [edn]
  (let [edn-keys (set (keys edn))
        edn-verbs (set/intersection edn-keys verbs)]
    (not (empty? edn-verbs))))

(defn includes-required-keys? [edn]
  (let [edn-keys (set (keys edn))]
    (set/subset? required-keys edn-keys)))

(defn name-is-string? [edn]
  (let [edn-name (get edn :name)]
    (= (type edn-name) java.lang.String)))

(defn base-url-is-env-or-string? [edn]
  (let [edn-base-url (get edn :base-url)
        base-url-name (name edn-base-url)]
    (or
     (= (type edn-base-url) java.lang.String)
     (str/starts-with? base-url-name ":env/"))))

(defn opts-is-map? [edn]
  (let [edn-opts (get edn :opts)]
    (= (type edn-opts) clojure.lang.PersistentArrayMap)))

(defn nav-is-map? [edn]
  (let [edn-nav (get edn :nav {})]
    (= (type edn-nav) clojure.lang.PersistentArrayMap)))

(defn extract-is-map? [edn]
  (let [edn-extract (get edn :extract {})]
    (= (type edn-extract) clojure.lang.PersistentArrayMap)))

(defn auth-is-map? [edn]
  (let [edn-auth (get edn :auth {})]
    (= (type edn-auth) clojure.lang.PersistentArrayMap)))

(def meets-requirements?
  (every-pred
   legal-keys? has-a-verb? includes-required-keys?
   name-is-string? base-url-is-env-or-string? opts-is-map?
   nav-is-map? extract-is-map? auth-is-map?))

(defn valid-edn? [edn]
  (meets-requirements? edn))
