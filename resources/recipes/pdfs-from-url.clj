 (require '[falcon.core :as c]
          '[falcon.extract :as x])

 (let [site-name (keyword (first *command-line-args*))
       s         (falcon.core/session site-name {:browser :firefox})
       urls      (distinct (falcon.extract/all-attr s [:pdf] :href))]
     (falcon.extract/save-many urls "pdf"))
  


    
