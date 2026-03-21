# Falcon

## Questions Commonly Moved (FAQ)

### What makest thou?
Falcon is an abstraction on top of Etaoin (the webdriver implementation for Clojure). I find myself doing a lot of manual website scraping, largely to harvest my own data. Sometimes I spot a pattern in how I scrape. For example, I frequently infinite-scroll until some condition is met, then copy all content meeting some other condition, and then add that content to a markdown-formatted file, possibly with an LLM in the loop to clean it up or whatevs. In that case, I'd break that pattern into composable bricks and code each brick as a Clojure function; scroll-until, copy-content-meeting-condition, format-as-mardkown, etc.

### Wherefore?
Because I get tired of selecting text with my mouse and then ctrl+v-ing into a textfile a gazillion times in one hour just to scrape some crap I wrote on a thothforsaken forum in 2009! On a more serious note: pretty much everything we do these days is done through a browser. And if you're a digital packrat like me, you want copies of everything and easy pathways for ingestion into RAG pipelines and so on. So it behooves me to make this.

### Whither? 
Right now I'm writing this for my own personal use and as a cool look-what-I-did-mom for Clojure meetups. So I'm not trying to scale it. However, I am leaving open the *possibility* of librarifying this. Consequently, I'm not going to write code "because that's what a library needs", **but** I'm avoiding anything that would prevent extension into a library. Keeping the door open, even though it doesn't need to scale right now.f

### Whereby?
Via the Clojure REPL. That means a command line. Without a GUI. If you want me to yell at you, please address me with a smarmy attitude and go "lol this code is cursed bro why so many small functions bro" BECAUSE IT'S A CLI TOOL YOU GOOBERS. It's meant to be used from that scary terminal thing. 

### Wherefore such an odd appellation?
Because a falcon is a helpful little creature that takes flight into the wild blue yonder and grabs whatever you need and brings it back to you with precision strikes.

Turning and turning in the widening gyre
The webapp cannot hear the developer
Things fall apart - the kludge can never hold
Rube-Goldberg code is loosed upon the world
The spaghetti-coded kitchen sink is loosed, and everywhere
Engineers are drowned in JS' fuster-clucked syntax

(they should hire me to write eulogies)


## Developer Manual

### Commandments

Don't break these.

1. **Thou shalt not define drivers or site configs at the top level of any namespace**. We're leaving the door open for this being extended into a bonafide library. Libraries are `required` into namespace. Top-level definitions with side effects are a great way to break anything that requires your library by poisoning the namespace (making it fail to load because `e/chrome` or whatever threw), . Therefore, they must not be declared at the top level of a namespace. Instead, drivers should be bound in REPL sessions or passed as function args.
2. **Thou shalt keep `resolve-env` and` load-site` logically separate**. There is a real logical difference between those two functions that must be maintained in order to keep the extensibility door open. `resolve-env` *transforms* data, walking the loaded config map and interpolating the values of env vars. By contrast, `load-site` *acquires* data. It's responsible for getting the `edn` config into your hands in the first place. `resolve-env` maps config with unresolved env var placeholders to usable config; `load-site` maps a site name to a config map that *may or may not be resolved*. Remember, this is REPL-based; if someone builds a config map by hand in the REPL, they might have env vars in there that require passing that map to `resolve-env`, and they're gonna be mighty cheesed off if `resolve-env` is not exposed independently from `load-site`.
3. **Thou shalt not hardcode thy favorite browser**. Yes, yes, I know, your browser is the *best* browser and other browsers are for n00bs who don't use your *perfect* browser because they're not the l33t n1nj4 haxx0r like you. And this also applies to your IDE, your OS, and your favorite ice cream. Fine. Just don't hardcode it.
4. **Thou shalt return the driver**. We write composable functions here. Much as Lego bricks can only connect because every brick as pegs on top and slots on bottom, Falconer functions are only composable if they always return the driver so you can thread them however you want.


### Dev Honor Code

Not life-and-death but try to do this more-or-less. Not actual rules. More of what ya might call... guidelines.

- Commit messages are declarative because I think it sounds cooler. Not "Make f return driver" but "f returns driver". If you create something, just state the name of the thing in your commit message: instead of "Create config for some-site" just say "Config for some-site".
- Comments, if they exist, should state WHY the code is there, not WHAT it does. WHAT the code does should be self-evident.
- "Boyscouting", or the fixing of small issues unrelated to the main PR's stated goal, is encouraged. :-)
- Please make use of LLMs or search engines instead of asking for translations of the Elizabethan terms in the FAQ. That's right, you snooty code-monkey, I demand literacy! Of books! With no code or equations!
- Acting irreverent and smarmy is fine as long as everybody is having a good time and you're not being an actual jerk. Feel free to make snide remarks in your PRs and commit messages about me, my code, my cat, and my face. My stupid, *stupid* face.

## Standards
These are actual, spec-level constraints on the code.


### Top-Level Shape Requirements for Site EDNs

The top level of a site EDN is a flat map with a fixed set of allowed keys. These rules ensure that every site EDN has the required identifying metadata and at least one actionable section. Validation of the top-level shape catches missing keys, misspelled keys, and wrong value types before any deeper structural checks run.

1. Only six keys are allowed at the top level: `:name`, `:base-url`, `:opts`, `:auth`, `:extract`, and `:nav`. Any other key is a validation error (catches typos like `:naav`).
2. `:name` is required. Its value must be a string.
3. `:base-url` is required. Its value must be a string or an `:env/`-namespaced keyword.
4. `:opts` is required. Its value must be a map.
5. At least one of `:auth`, `:nav`, or `:extract` must be present, and its value must be a map.

### Leaf Node Structure in Site EDNs

A leaf is a map that marks the boundary between the intent tree (navigated by the user at the REPL) and the function payload (consumed opaquely by Falcon functions). The presence of a `:q` key is what distinguishes a leaf from an interior node. Everything above the leaf in the intent tree is the user's concern; everything at and below the leaf is the consuming function's concern. A leaf has exactly three allowed top-level keys:

6. **`:q`** (required, map) — The DOM locator. Must contain exactly one key from the known locator set: `:css`, `:xpath`, `:tag`, `:id`, `:name`, `:class`. If the locator key is `:css`, its value must be a non-empty, non-whitespace string.
7. **`:attr`** (optional, keyword) — What to extract from the located element. If present, must be a member of `falcon.core/supported-attrs`. If absent, the consuming function decides what to do with the element.
8. **`:params`** (optional, map) — Opaque payload for the consuming function. The validator does not inspect its contents. Internal structure, including further nesting, is the consuming function's concern.
9. No other top-level keys are permitted on a leaf. A key that is not `:q`, `:attr`, or `:params` is a validation error.

### Intent Tree Structure for `:nav` and `:extract`

The `:nav` and `:extract` values are intent trees — nested maps where key paths form human-readable descriptions of actions or targets. `resolve-intent` walks these trees to translate a keyword path into a leaf. The key path should read like a sentence fragment: `[:nav :search :profiles]` reads as "the nav search for profiles." The following rules ensure that intent trees are well-formed and that every path leads to a usable DOM binding.

10. Every non-leaf value in the tree must be a map. Bare primitives (strings, numbers, keywords, booleans) and empty maps are not permitted as values in the tree.
11. Every path through the tree must terminate at a leaf. That is, recursive descent through map values must eventually reach a map containing `:q`.

### Auth Shape Requirements

Auth is structurally similar to a nav action — it represents the intent "log in" with DOM bindings for form fields, a submit button, and a success indicator. It earns its own top-level key because it is a precondition for all other actions, it holds secrets (`:env/`-namespaced keywords), and it has distinctive success/failure semantics. These rules apply when `:auth` is present in the site EDN. OAuth and other non-form-based auth flows are out of scope for now.

12. `:auth` must contain `:fields` (a map) and `:submit` (a leaf with `:q`).
13. Each field under `:auth :fields` must satisfy all leaf node rules (rules 6–9) and must additionally contain a `:value` key.
14. The value of `:value` must be either a string or an `:env/`-namespaced keyword.
15. `:env/`-namespaced keywords must only appear inside `:auth`. Their presence anywhere else in the site EDN is a validation error.



### Leaf Algorithm

A leaf node has this form: 
{:q {:locator-type locator} 
    :attr some_attr
    :params some_map}

A valid leaf:
0. Has a set of keys containing `:q`
1. Has some subset of {`:q`, `:attrs`, `:params`} for its keys
2. Has a value for `:q` that is a map (call it the q-map)
3. has a qmap with exactly one key that is in `locator-types`; if that key is `:css`, its value is a non-blank, nonempty string
4. If it has attr as a key, maps attr to one of `#{:text :href :src :alt :value :title :class :id :inner-html :outer-html}`
5. If it has :params as a key, the value of `params` is a map 
