(ns clecs.backend.atom-world-test
  (:require [clecs.backend.atom-world :refer :all]
            [clecs.backend.atom-world.query :as query]
            [clecs.component :refer [validate]]
            [clecs.test.checkers :refer :all]
            [clecs.world :as world]
            [midje.sweet :refer :all]))


(def editable-world-like (implements-protocols world/IEditableWorld
                                               world/IQueryableWorld))


;; World Initialization.


(fact "Atom world implements IWorld."
      (atom-world ..components.. --init-- ..systems..) => (implements-protocols world/IWorld))


(fact "Initialization function is called within a transaction."
      (atom-world ..components.. --init-- ..systems..) => irrelevant
      (provided (--init-- (as-checker editable-world-like)) => irrelevant))


(fact "transaction! calls function with an editable world and time delta."
      (let [w (->AtomWorld nil (atom ..state..) ..editable-world..)]
        (-transaction! w --f-- ..dt..) => nil
        (provided (--f-- ..editable-world.. ..dt..) => irrelevant)))


;; Processing

(fact "process! returns the world."
      (let [w (atom-world ..components.. --init-- ..systems..)]
        (world/process! w ..dt..) => w))


(fact "process! calls each system with the world and delta time."
      (let [calls (atom [])
            s-one {:name :s-one
                   :process (fn [& args] (swap! calls conj [:one-called args]))}
            s-two {:name :s-two
                   :process (fn [& args] (swap! calls conj [:two-called args]))}
            w (atom-world ..components.. --init-- [s-one s-two])]
        (world/process! w ..dt..) => irrelevant
        (set (map first @calls)) => (set [:one-called :two-called])
        (-> @calls first second first) => editable-world-like
        (-> @calls first second second) => ..dt..
        (-> @calls second second first) => editable-world-like
        (-> @calls second second second) => ..dt..))


;; Editable world.

(fact "world/-component returns the component definition."
      (let [w (->AtomEditableWorld {::TestComponentA ..c..})]
        (world/-component w ::TestComponentA) => ..c..))


(fact "world/-component returns nil if cnam is not recognized."
      (let [w (->AtomEditableWorld {::TestComponentA ..c..})]
        (world/-component w ::TestComponentC) => nil))


(fact "world/-set-component adds the component if entity doesn't have one."
      (let [eid 1
            cdata {}
            w (->AtomEditableWorld {::TestComponentA ..c..})
            initial-state {:components {}
                           :entities {eid #{}}}
            expected-state {:components {::TestComponentA {eid cdata}}
                            :entities {eid #{::TestComponentA}}}]
        (binding [*state* initial-state]
          (world/-set-component w eid ::TestComponentA cdata) => w
          (provided (validate anything anything) => nil)
          *state* => expected-state)))


(fact "world/-set-component replaces existing components."
      (let [eid 1
            c-old {:a ..a.. :b ..b..}
            c-new {:a ..c.. :b ..d..}
            w (->AtomEditableWorld {::TestComponentB ..c..})
            initial-state {:components {::TestComponentB {eid c-old}}
                            :entities {eid #{::TestComponentB}}}
            expected-state {:components {::TestComponentB {eid c-new}}
                            :entities {eid #{::TestComponentB}}}]
        (binding [*state* initial-state]
          (world/-set-component w eid ::TestComponentB c-new) => w
          (provided (validate anything anything) => nil)
          *state* => expected-state)))


(fact "world/-set-component validates component names."
      (world/-set-component (->AtomEditableWorld nil)
                           ..eid..
                           ::TestComponentB
                           {:a ..a.. :b ..b..}) => (throws RuntimeException
                                                           #"Unknown"))


(fact "world/-set-component validates components."
      (let [w (->AtomEditableWorld {::TestComponentA ..c..})
            initial-state {:components {}
                           :entities {..eid.. #{}}}]
        (binding [*state* initial-state]
          (world/-set-component w ..eid.. ::TestComponentA ..cdata..) => w
          (provided (validate ..c.. ..cdata..) => anything))))


(fact "world/add-entity returns a new entity id."
      (binding [*state* {:last-entity-id 0}]
        (world/add-entity (->AtomEditableWorld nil)) => 1)
      (binding [*state* {:last-entity-id 41}]
        (world/add-entity (->AtomEditableWorld nil)) => 42))


(fact "world/add-entity adds the new entity-id to the entity index."
      (binding [*state* {:last-entity-id 0}]
        (let [eid (world/add-entity (->AtomEditableWorld nil))]
          (get-in *state* [:entities eid]) => #{})))


(fact "world/add-entity updates entity counter."
      (binding [*state* {:last-entity-id 0}]
        (world/add-entity (->AtomEditableWorld nil))
        (:last-entity-id *state*) => 1))


(fact "world/add-entity returns different values each time it's called."
      (let [w (->AtomEditableWorld nil)]
        (binding [*state* {:entities {}
                           :last-entity-id 0}]
          (repeatedly 42 #(world/add-entity w)) => (comp #(= % 42) count set))))


(fact "world/component resolves queried component."
      (binding [*state* {:components {..cname.. {..eid.. ..component..}}}]
        (world/component (->AtomEditableWorld nil) ..eid.. ..cname..) => ..component..))


(fact "world/query returns a seq."
      (binding [*state* {:entities {..e1.. #{..c1.. ..c2..}
                                    ..e2.. #{..c2..}}}]
        (world/query (->AtomEditableWorld nil) ..q..) => seq?
        (provided (query/-compile-query ..q..) => (partial some #{..c1..}))))


(fact "world/query compiles query and then calls the result with a seq of component labels."
      (binding [*state* {:entities {..e1.. #{..c1.. ..c2..}
                                    ..e2.. #{..c2..}
                                    ..e3.. #{..c3..}}}]
        (sort-by str (world/query (->AtomEditableWorld nil) ..q..)) => [..e1.. ..e3..]
        (provided (query/-compile-query ..q..) => (partial some #{..c1.. ..c3..}))))


(fact "world/remove-component works."
      (let [w (->AtomEditableWorld nil)
            initial-state {:components {..cname.. {..eid.. ..component..}}
                           :entities {..eid.. #{..cname..}}}
            expected-state {:components {..cname.. {}}
                            :entities {..eid.. #{}}}]
        (binding [*state* initial-state]
          (world/remove-component w ..eid.. ..cname..) => w
          *state* => expected-state)))


(fact "world/remove-entity removes entity-id from entity index."
      (let [w (->AtomEditableWorld nil)]
        (binding [*state* {:components {}
                           :entities {1 #{}}
                           :last-entity-id 1}]
          (world/remove-entity w 1) => w
          *state* => {:components {}
                      :entities {}
                      :last-entity-id 1})))


(fact "world/remove-entity removes entity's components."
      (let [cname ::TestComponentA
            w (->AtomEditableWorld nil)
            initial-state {:components {cname {..eid.. ..i.. ..other-eid.. ..j..}}
                           :entities {}}
            expected-state {:components {cname {..other-eid.. ..j..}}
                            :entities {}}]
        (binding [*state* initial-state]
          (world/remove-entity w ..eid..) => w
          *state* => expected-state)))
