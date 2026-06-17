(ns falcon.auth
  (:require [etaoin.api :as e]))

;; ---- Predicates ----

(defn logged-in?
  "True when the site's :auth :success element is present.
  Useful for detecting expired sessions mid-scrape."
  [driver {:keys [auth] :as _site}]
  (e/exists? driver (get-in auth [:success :q])))

(defn challenge-pending?
  "True when the site's :auth :challenge field is present — i.e. the site is
  parked on a 2FA / verification-code screen waiting for input. Returns nil
  when the site config declares no :challenge."
  [driver {:keys [auth] :as _site}]
  (when-let [q (get-in auth [:challenge :q])]
    (e/exists? driver q)))

;; ---- Outcome detection ----

(defn await-outcome
  "After credentials are submitted, poll until the site lands on either the
  :success element or the :challenge screen. Returns :success or :challenge.
  Throws on timeout. Deliberately avoids assuming a challenge always appears —
  a remembered device may log straight through to success."
  ([driver site] (await-outcome driver site {}))
  ([driver site {:keys [timeout interval] :or {timeout 30 interval 0.5}}]
   (let [deadline (+ (System/currentTimeMillis) (long (* timeout 1000)))]
     (loop []
       (cond
         (logged-in? driver site)         :success
         (challenge-pending? driver site) :challenge
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "Timed out waiting for login outcome (neither success nor challenge element appeared)."
                         {:site (:name site) :timeout timeout}))
         :else (do (e/wait driver interval) (recur)))))))

;; ---- Primitives: the manual two-phase API ----

(defn fill-credentials!
  "Navigate to the :auth login URL and fill + submit the credential fields.
  Returns the driver parked wherever the site lands — the success page OR a
  verification-code (challenge) screen. Pair with submit-challenge! when a
  2FA code is required."
  [driver {:keys [auth] :as _site}]
  (let [{:keys [login-url fields submit]} auth]
    (e/go driver login-url)
    (doseq [[_field-name {:keys [q value]}] fields]
      (e/wait-visible driver q)
      (e/fill driver q value))
    (e/click driver (:q submit))
    driver))

(defn submit-challenge!
  "Fill the verification code into the site's :auth :challenge field, submit
  it, and wait for the :success element. `code` is the code as a string.
  Returns the driver, logged in."
  [driver {:keys [auth] :as _site} code]
  (let [{:keys [challenge success]} auth]
    (e/wait-visible driver (:q challenge))
    (e/fill driver (:q challenge) code)
    (e/click driver (get-in challenge [:submit :q]))
    (e/wait-visible driver (:q success))
    driver))

;; ---- Orchestrator: pluggable code source ----

(defn login!
  "Log in using the site's :auth config.

  One-shot when no challenge screen appears (behaves like a plain form login).
  When the site presents a verification-code screen, the code is obtained by
  calling code-fn — a 0-arg function returning the code string — and the login
  is completed. Returns the driver, logged in.

  code-fn is the extensibility seam:
    manual REPL:   {:code-fn #(do (print \"code: \") (flush) (read-line))}
    email capture: {:code-fn (gmail-code-fn {...})}   ; future

  For an explicit, non-blocking manual flow at the REPL, skip login! and call
  fill-credentials! then submit-challenge! directly."
  ([driver site] (login! driver site {}))
  ([driver site {:keys [code-fn] :as _opts}]
   (fill-credentials! driver site)
   (case (await-outcome driver site)
     :success   driver
     :challenge (if code-fn
                  (submit-challenge! driver site (code-fn))
                  (throw (ex-info "Login needs a verification code but no :code-fn was supplied. Pass {:code-fn ...} or use fill-credentials! + submit-challenge! manually."
                                  {:site (:name site)}))))))
