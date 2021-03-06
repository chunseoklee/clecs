(ns clecs.query
  "Primitives for query criteria.

  `query` method of [[clecs.world.queryable/IQueryableWorld]]
  is used to find entities based on their components.

  #### Examples:

      ;; Return a seq containing entity id's that
      ;; has FooComponent:
      (query queryable-world (all FooComponent))

      ;; Same as the previous one:
      (query queryable-world (any FooComponent))

      ;; Entities with both FooComponent and BarComponent
      (query queryable-world
             (all FooComponent BarComponent))

      ;; Entities with either FooComponent or BarComponent
      (query queryable-world
             (any FooComponent BarComponent))

      ;; Entities with FooComponent and either
      ;; BazComponent or BatComponent
      (query queryable-world
             (all FooComponent (any BazComponent
                                    BatComponent)))

      ;; Entities with either FooComponent and
      ;; BarComponent or BazComponent and BatComponent
      (query queryable-world
             (any (all FooComponent BarComponent)
                  (all BazComponent BatComponent)))

  You can nest primitive calls infinitely. However
  it is considered bad design to query too many
  components at once.

  See also [[clecs.world.queryable/IQueryableWorld]]."
  (:import [clojure.lang Keyword]))


(declare recursively-components
         simplify-children)


(defprotocol ^:no-doc IQueryNode
  (satisfies [this components])
  (simplify [this]))


(defrecord Query [root components]
  clojure.lang.IFn
  (invoke [this cs] (satisfies this (set cs)))
  IQueryNode
  (satisfies [_ cs] (satisfies root cs))
  (simplify [_]
            (let [new-root (simplify root)
                  cs (recursively-components new-root)]
              (->Query new-root cs))))


(defrecord All [children]
  IQueryNode
  (satisfies [_ components] (loop [[head & tail] (seq children)]
                              (if (nil? head)
                                true
                                (if (satisfies head components)
                                  (recur tail)
                                  false))))
  (simplify [_] (simplify-children All ->All children)))


(defrecord Any [children]
  IQueryNode
  (satisfies [_ components] (loop [[head & tail] (seq children)]
                              (if (nil? head)
                                false
                                (if (satisfies head components)
                                  true
                                  (recur tail)))))
  (simplify [_] (simplify-children Any ->Any children)))


(extend-protocol IQueryNode
  Keyword
  (satisfies [this components] (contains? components this))
  (simplify [this] this))


(defn- query [constructor elems]
  (if (seq elems)
    (-> (set elems)
        (constructor)
        (->Query nil)
        (simplify))
    (throw (IllegalArgumentException. "You cannot create an empty query"))))


(defn accessed [q]
  (:components q))


(def ^{:deprecated "2.1.0"} accesses accessed)


(defn all [& elems]
  (query ->All elems))


(defn any [& elems]
  (query ->Any elems))


(defn- recursively-components
  "Recursively walking root, Return a set of all component names used."
  [root]
  (->> root
       (tree-seq (partial satisfies? IQueryNode) :children)
       (filter keyword?)
       (set)))


(defn- simplify-children
  "Simplify, flatten & inline children with respect to node-type."
  [node-type constructor children]
  (let [f (fn [acc elem]
            (cond
             ;; Not a query, add as is.
             (not (instance? Query elem)) (conj acc elem)
             ;; A query with root node with the same type, inline its children.
             (instance? node-type (:root elem)) (into acc (get-in elem [:root :children]))
             ;; A query with a root node with one element, inline the element.
             (= (count (get-in elem [:root :children])) 1) (conj acc (first (get-in elem [:root :children])))
             ;; A query with root node of different type add the root node.
             :else (conj acc (:root elem))))
        new-children (->> children
                          (map simplify)
                          (reduce f #{}))]
    (if (and (= (count new-children) 1)
             (not (keyword? (first (seq new-children))))
             (satisfies? IQueryNode (first (seq new-children))))
      (first new-children)
      (constructor new-children))))


;; Hide internals from documentation generator.
(doseq [v [#'->All
           #'map->All
           #'->Any
           #'map->Any
           #'->Query
           #'map->Query]]
  (alter-meta! v assoc :no-doc true))
