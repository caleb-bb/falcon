# Falcon

Falcon is a REPL-driven web scraping toolkit for Clojure, built on top of [etaoin](https://github.com/igrishaev/etaoin). It turns the repetitive grunt work of browser-based scraping — login, scroll, click, extract, repeat — into composable functions parameterized by site-specific configuration files.

## Why

Pretty much everything we do these days happens through a browser. If you want to get your own data back out — forum posts, feed content, profile information — you're stuck selecting text with your mouse and pasting it into a file a hundred times. Falcon automates that loop from the Clojure REPL so you can wire up a new site in a few minutes and scrape it programmatically.

## How it works

Falcon separates the *verbs* (what you want to do) from the *nouns* (where to do it on a specific site).

The verbs are Falcon's functions: log in, scroll a feed, click a tab, search, extract structured data. These don't change between sites.

The nouns live in **site EDN files** — per-site configuration maps stored in `resources/sites/`. Each EDN describes a website's DOM structure in terms Falcon's functions can consume: CSS selectors for login fields, scroll sentinels, clickable links, extractable content. The person at the REPL thinks in intent ("scroll the infinite feed") and the EDN translates that intent into DOM reality.

```clojure
;; Start a session
(def s (f/session :example-site {:headless false}))
(def d (:driver s))
(def site (:site s))

;; Compose verbs against the site config
(-> d
    (auth/login! site)
    (nav/do! site [:search :profiles] "Sean Kernan")
    (nav/do! site [:click :answers])
    (nav/do! site [:scroll :infinite]))

(def answers (extract/extract-all d site))
```

The etaoin driver is always directly accessible. When Falcon's abstractions don't cover an edge case, you drop down to raw etaoin calls without leaving the REPL.

## Architecture

### Namespaces

- **`falcon.core`** — Config loading (`load-site`), environment variable resolution (`resolve-env`), browser lifecycle (`start`, `stop`, `session`). The machinery that gets a site EDN and a browser driver into your hands.
- **`falcon.auth`** — Form-based login. Takes a driver and site config, fills credentials, clicks submit, waits for the success indicator.
- **`falcon.nav`** — Multi-step browser recipes dispatched by verb. A single `do!` function reads a path like `[:scroll :infinite]` from the site EDN, looks up the DOM bindings, and runs the appropriate recipe (scroll, click, search, paginate). Also exposes pure introspection functions (`verbs`, `targets`, `describe`, `tree`) for exploring what a site EDN supports.
- **`falcon.extract`** — Turns loaded pages into Clojure data. Reads `:extract` from the site config to find container elements and pull named fields from each one.

### Site EDNs

A site EDN has six top-level keys:

- **`:name`** — Human-readable site name.
- **`:base-url`** — The site's root URL (string or `:env/` keyword).
- **`:auth`** — Login flow: URL, form fields, submit button, success indicator. The only place `:env/`-namespaced keywords (secrets) may appear.
- **`:nav`** — Registry of named navigation actions, organized by verb (`:click`, `:scroll`, `:search`, `:paginate`). Interior nodes are human-readable intent paths; leaf nodes are DOM bindings with a `:q` key.
- **`:extract`** — Registry of named extractable things, each with a container selector and field mappings.
- **`:opts`** — Per-site behavioral config: pause durations, scroll limits, retry policy. These are user preferences, not DOM facts, so they stay out of the intent tree.

The key architectural insight is that site EDNs are **adapter layers** between Falcon's stable verb vocabulary and unstable website DOMs. When a site changes its markup, you regenerate the EDN. The Falcon code stays the same.

### Leaf nodes

Every intent path in the EDN terminates at a leaf — a map with a `:q` key containing a locator:

```clojure
{:q {:css ".answer-text"} :attr :text}
```

The `:q` map holds exactly one locator type (`:css`, `:xpath`, `:tag`, `:id`, `:name`, `:class`). An optional `:attr` key says what to extract from the element. An optional `:params` key holds function-specific payload (like a submit button for a search field). Behavioral parameters like pause durations never appear on leaves — they live in `:opts`.

## Future direction

The long-term goal is **programmatic generation of site EDNs**. Instead of hand-writing the config for each site, Falcon would accept a block of HTML (or a live page) and produce an EDN that maps its DOM structure to Falcon's verb vocabulary.

The generation strategy is phased: start with LLM-based generation (prompt an LLM with the site EDN schema and the target HTML), accumulate enough mappings to discover heuristics, then replace the expensive parts with rule-based systems and lightweight classifiers. The LLM becomes a fallback for genuinely novel layouts, not the default path. Most of the work — element classification by verb, semantic naming from attributes and visible text — reduces to extraction and normalization rather than generation.

This isn't being built yet. Right now the focus is on making the core vocabulary solid and the REPL experience fast.

## Setup

Requires Clojure 1.12+ and a WebDriver binary on your PATH (e.g. `chromedriver`).

```bash
# Check what drivers are available
clj -M -e "(require '[falcon.core :as f]) (f/check-drivers!)"

# Start a REPL
clj -M:repl
```

## Running tests

```bash
clj -M:test
```

Uses `cognitect.test-runner`. Test site EDNs live in `test-resources/sites/` behind the `:test` alias.

## Project rules

These are non-negotiable.

**No top-level side effects.** Don't define drivers or site configs at the top level of any namespace. This codebase may become a library someday, and libraries that blow up on `require` because `e/chrome` threw are useless. Drivers get bound in REPL sessions or passed as function args.

**`load-site` and `resolve-env` stay separate.** `load-site` acquires data (reads an EDN file). `resolve-env` transforms data (interpolates environment variables). Someone building a config map by hand at the REPL needs `resolve-env` independently.

**No hardcoded browsers.** Browser type is always parameterized.

**Always return the driver.** Every effectful function that touches the browser returns the driver so you can thread it. This is what makes Falcon composable.

## Conventions

- Commit messages are declarative: "f returns driver" not "Make f return driver." For new things, just state the name: "Config for some-site."
- Comments explain *why*, not *what*. What the code does should be self-evident.
- Boyscouting encouraged — fix small things as you encounter them.
