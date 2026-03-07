(ns falcon.core
  (:require [etaoin.api :as e]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]))

;; ---- Config loading ----

(defn load-site
  "Load a website edn from the sites/directory by keyword name.
  Returns the raw config map with :env/ keywords unresolved.
  Example: (load-site :example-site)"
  [site-key]
  (->  (str "sites/" (name site-key) ".edn")
       io/resource
       slurp
       edn/read-string))

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
              value (System/getenv var-name)]
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

;; ---- Convenience: full session ----

(defn session
  "Load a site config, resolve env vars, start a browser, return both.
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
     {:driver driver :site site})))
