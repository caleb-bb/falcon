(ns falcon.auth
  (:require [etaoin.api :as e]))

(defn login!
  "Perform a form-based login using the site's config's :auth map.
  Navigates to the login URL, fills each field, clicks submit,
  and waits for the success element. Returns the driver."
  [driver {:keys [auth] :as _site}]
  (let [{:keys [login-url fields submit success]} auth]
    (e/go driver login-url)
    (doseq [[_field-name {:keys [q value]}] fields]
      (e/wait-visible driver q)
      (e/fill driver q value))
    (e/click driver (:q submit))
    (e/wait-visible driver (:q success))
    driver))

(defn logged-in?
  "Check whether the success element from the auth config is visible.
  Useful for detecting expired sessions mid-scrape."
  [driver {:keys [auth] :as _site}]
  (e/exists? driver (get-in auth [:success :q])))

(ns falcon.auth
  (:require [etaoin.api :as e]))
