(ns clecs.backend.atom-world.transactable
  (:require [clecs.backend.atom-world.state :refer [*state*
                                                    -ensure-no-transaction]]))


(defn -transaction! [world f]
  (-ensure-no-transaction)
  (swap! (.state world)
         (fn [state]
           (binding [*state* state]
             (f world)
             *state*)))
  nil)
