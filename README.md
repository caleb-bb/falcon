# Falcon

## What are you doing!?
Falcon is an abstraction on top of Etaoin (the webdriver implementation for Clojure). I find myself doing a lot of manual website scraping, largely to harvest my own data. Sometimes I spot a pattern in how I scrape. For example, I frequently infinite-scroll until some condition is met, then copy all content meeting some other condition, and then add that content to a markdown-formatted file, possibly with an LLM in the loop to clean it up or whatevs. In that case, I'd break that pattern into composable bricks and code each brick as a Clojure function; scroll-until, copy-content-meeting-condition, format-as-mardkown, etc.

## Why?
Because I get tired of selecting text with my mouse and then ctrl+v-ing into a textfile a gazillion times in one hour just to scrape some crap I wrote on a thothforsaken forum in 2009! On a more serious note: pretty much everything we do these days is done through a browser. And if you're a digital packrat like me, you want copies of everything and easy pathways for ingestion into RAG pipelines and so on. So it behooves me to make htis.

## How?
Via the Clojure REPL. That means a command line. Without a GUI. If you want me to yell at you, please address me with a smarmy attitude and go "lol this code is cursed bro why so many small functions bro" BECAUSE IT'S A CLI TOOL YOU GOOBERS. It's meant to be used from that scary terminal thing. 

## It's called Falcon?
Yes. Because it's a helpful little creature that takes flight into the wild blue yonder and grabs whatever you need and brings it back to you with precision strikes.

Turning and turning in the widening gyre
The webapp cannot hear the developer
Things fall apart - the kludge can never hold
Rube-Goldberg code is loosed upon the world
The spaghetti-coded kitchen sink is loosed, and everywhere
Engineers are drowned in JS' fuster-clucked syntax

(they should hire me to write eulogies)
