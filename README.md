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


## Developer Guidelines

1. **Thou shalt not define drivers or site configs at the top level of any namespace**. We're leaving the door open for this being extended into a bonafide library. Libraries are `required` into namespace. Top-level definitions with side effects are a great way to break anything that requires your library by poisoning the namespace (making it fail to load because `e/chrome` or whatever threw), . Therefore, they must not be declared at the top level of a namespace. Instead, drivers should be bound in REPL sessions or passed as function args.
2. **Thou shalt keep `resolve-env` and` load-site` logically separate**. There is a real logical difference between those two functions that must be maintained in order to keep the extensibility door open. `resolve-env` *transforms* data, walking the loaded config map and interpolating the values of env vars. By contrast, `load-site` *acquires* data. It's responsible for getting the `edn` config into your hands in the first place. `resolve-env` maps config with unresolved env var placeholders to usable config; `load-site` maps a site name to a config map that *may or may not be resolved*. Remember, this is REPL-based; if someone builds a config map by hand in the REPL, they might have env vars in there that require passing that map to `resolve-env`, and they're gonna be mighty cheesed off if `resolve-env` is not exposed independently from `load-site`.
3. **Thou shalt not hardcode thy favorite browser**. Yes, yes, I know, your browser is the *best* browser and other browsers are for n00bs who don't use your *perfect* browser because they're not the l33t n1nj4 haxx0r like you. And this also applies to your IDE, your OS, and your favorite ice cream. Fine. Just don't hardcode it.
4. **Thou shalt return the driver**. We write composable functions here. Much as Lego bricks can only connect because every brick as pegs on top and slots on bottom, Falconer functions are only composable if they always return the driver so you can thread them however you want.
