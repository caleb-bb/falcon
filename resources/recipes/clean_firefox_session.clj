(require '[falcon.core]
         '[falcon.extract])

(def site-name (keyword (first *command-line-args*)))
(def sesh (falcon.core/session "substack" {:browser :firefox}))
(def urls (distinct (falcon.extract/all-attr s [:pdf] :href)))
  


    
