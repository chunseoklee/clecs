(ns clecs.backend.atom-world
  "Reference implementation of clecs API.

  `AtomWorld` stores it's data in-memory. It is backed by an
  `clojure.core/atom` internally.

  Currently systems run sequentially."
  (:require [clecs.query :refer [accessed]]
            [clecs.util :refer [map-values]]
            [clecs.world :refer [-run
                                 IEditableWorld
                                 IQueryableWorld
                                 IWorld
                                 IWorldFactory]]
            [clojure.set :refer [difference
                                 subset?
                                 union]]))


(def ^:no-doc initial_state {:components {}
                             :entities {}
                             :last-entity-id 0})


(def ^{:dynamic true
       :no-doc true} *state*)


(deftype AtomEditableWorld [readables writables]
  IEditableWorld
  (add-entity [_]
              (let [state *state*
                    eid (inc (:last-entity-id state))]
                (var-set #'*state*
                         (-> state
                             (assoc-in [:entities eid] #{})
                             (assoc :last-entity-id eid)))
                eid))
  (remove-component [this eid cname]
                    (when-not (contains? writables cname)
                      (throw (RuntimeException. (str "Unknown component " cname))))
                    (var-set #'*state*
                             (-> *state*
                                 (update-in [:entities eid] disj cname)
                                 (update-in [:components cname] dissoc eid)))
                    this)
  (remove-entity [this eid]
                 (let [state *state*]
                   (var-set #'*state*
                            (-> state
                                (update-in [:entities] dissoc eid)
                                (update-in [:components]
                                           (partial map-values #(dissoc % eid))))))
                 this)
  (-set-component [this eid cname cdata]
                  (var-set #'*state*
                           (-> *state*
                               (update-in [:entities eid] conj cname)
                               (update-in [:components cname] #(or % {}))
                               (update-in [:components cname] conj [eid cdata])))
                  this)
  IQueryableWorld
  (-component [_ cname] (writables cname))
  (component [_ eid cname]
             (when-not (contains? readables cname)
               (throw (RuntimeException. (str "Unknown component " cname))))
             (get-in *state* [:components cname eid]))
  (query [_ q]
         (when-not (subset? (accessed q) readables)
           (throw (RuntimeException. (str "Unknown components " (difference (accessed q)
                                                                            readables)))))
         (reduce-kv (fn [coll k v]
                      (if (q (seq v))
                        (conj coll k)
                        coll))
                    (seq [])
                    (:entities *state*))))


(deftype AtomWorld [components systems state]
  IWorld
  (-run [this readables writables f dt]
        (swap! (.state this)
               (fn [state]
                 (binding [*state* state]
                   (f (->AtomEditableWorld readables writables) dt)
                   *state*)))
        this)
  (process! [this dt]
            (doseq [s (vals systems)
                    :let [readables (union (:reads s) (:writes s))
                          writables (select-keys components (:writes s))]]
              (-run this readables writables s dt))
            this))


(def atom-world-factory
  "
  Factory for creating atom-world's.

  See also [[clecs.world/world]].
  "
  (reify
    IWorldFactory
    (-supported-types [_] #{Boolean Integer String})
    (-world [_ components systems _] (->AtomWorld components
                                                  systems
                                                  (atom initial_state)))))


;; Hide internals from documentation generator.
(doseq [v [#'->AtomWorld
           #'->AtomEditableWorld]]
  (alter-meta! v assoc :no-doc true))
