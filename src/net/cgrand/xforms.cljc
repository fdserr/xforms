(ns net.cgrand.xforms
  "Extra transducers for Clojure"
  {:author "Christophe Grand"}
  (:refer-clojure :exclude [reduce into count for partition str juxt first])
  (:require #?(:clj [clojure.core :as clj]
               :cljs [cljs.core :as clj :refer-macros [for]])))

(defmacro for
  "Like clojure.core/for with the first expression being replaced by % (or _). Returns a transducer."
  [[binding %or_ & seq-exprs] body]
  (assert (and (symbol? %or_) (#{"%" "_"} (name %or_)))
    "The second element of the comprehension vector must be % or _.")
  (let [rf (gensym 'rf)
        acc (gensym 'acc)
        body
        (clj/reduce (fn [body [expr binding]]
                      (case binding
                        :let `(let ~expr ~body)
                        :when `(if ~expr ~body ~acc)
                        :while `(if ~expr ~body (reduced ~acc))
                        `(clj/reduce (fn [~acc ~binding] ~body) ~acc ~expr)))
          `(~rf ~acc ~body)
          (clj/partition 2 (rseq (vec seq-exprs))))]
    `(fn [~rf]
       (fn
         ([] (~rf))
         ([~acc] (~rf ~acc))
         ([~acc ~binding] ~body)))))

(defprotocol KvRfable "Protocol for reducing fns that takes key and val as separate arguments."
  (some-kvrf [f] "Returns a kvrf or nil"))

(extend-protocol KvRfable
  #?(:clj Object :cljs object) (some-kvrf [_] nil)
  nil (some-kvrf [_] nil))

(defmacro kvrf [name? & fn-bodies]
  (let [name (if (symbol? name?) name? (gensym '_))
        fn-bodies (if (symbol? name?) fn-bodies (cons name? fn-bodies))
        fn-bodies (if (vector? (clj/first fn-bodies)) (list fn-bodies) fn-bodies)]
    `(reify
       clojure.lang.Fn
       KvRfable
       (some-kvrf [this#] this#)
       clojure.lang.IFn
       ~@(clj/for [[args & body] fn-bodies]
           `(invoke [~name ~@args] ~@body)))))

#_(defn reduce
  "A transducer that reduces a collection to a 1-item collection consisting of only the reduced result.
   Unlike reduce but like transduce it does call the completing arity (1) of the reducing fn."
  ([f]
    (fn [rf]
      (let [vacc (volatile! (f))]
        (if-some [f (some-kvrf f)]
          (kvrf
            ([] (rf))
            ([acc] (rf (unreduced (rf acc (f (unreduced @vacc))))))
            ([acc x]
              (if (reduced? (vswap! vacc f x))
                (reduced acc)
                acc))
            ([acc k v]
              (if (reduced? (vswap! vacc f k v))
                (reduced acc)
                acc)))
          (fn
            ([] (rf))
            ([acc] (rf (unreduced (rf acc (f (unreduced @vacc))))))
            ([acc x]
              (if (reduced? (vswap! vacc f x))
                (reduced acc)
                acc)))))))
  ([f init]
    (reduce (fn ([] init) ([acc] (f acc)) ([acc x] (f acc x))))))

#_(defn- into-rf [to]
  (cond
    (instance? clojure.lang.IEditableCollection to)
    (if (map? to)
      (kvrf
        ([] (transient to))
        ([acc] (persistent! acc))
        ([acc x] (conj! acc x))
        ([acc k v] (assoc! acc k v)))
      (fn
        ([] (transient to))
        ([acc] (persistent! acc))
        ([acc x] (conj! acc x))))
    (map? to)
    (kvrf
      ([] to)
      ([acc] acc)
      ([acc x] (conj acc x))
      ([acc k v] (assoc acc k v)))
    :else
    (fn
      ([] to)
      ([acc] acc)
      ([acc x] (conj acc x)))))

#_(defn into
  "Like clojure.core/into but with a 1-arg arity returning a transducer which accumulate every input in a collection and outputs only the accumulated collection."
  ([to]
    (reduce (into-rf to)))
  ([to from]
    (into to identity from))
  ([to xform from]
    (let [rf (xform (into-rf to))]
      (if-let [rf (and (map? from) (satisfies? clojure.core.protocols/IKVReduce from) (some-kvrf rf))]
        (rf (clj/reduce-kv rf (rf) from))
        (rf (clj/reduce rf (rf) from))))))

#_(defmacro ^:private or-instance? [class x y]
  (let [xsym (gensym 'x_)]
    `(let [~xsym ~x]
       (if (instance? ~class ~xsym) ~(with-meta xsym {:tag class}) ~y))))

#_(defn str!
  "Like xforms/str but returns a StringBuilder."
  ([] (StringBuilder.))
  ([sb] (or-instance? StringBuilder sb (StringBuilder. (clj/str sb)))) ; the instance? checks are for compatibility with str in case of seeded reduce/transduce.
  ([sb x] (.append (or-instance? StringBuilder sb (StringBuilder. (clj/str sb))) x)))

#_(def str
  "Reducing function to build strings in linear time. Acts as replacement for clojure.core/str in a reduce/transduce call."
  (completing str! clj/str))

;; for both map entries and vectors
#_(defn- key' [kv] (nth kv 0))
#_(defn- val' [kv] (nth kv 1))

#_(defn- noprf "The noop reducing function" ([acc] acc) ([acc _] acc))

#_(defn- multiplexable
  "Creates a multiplexable reducing function (doesn't init or complete the uderlying rf)."
  [rf]
  (fn ([]) ([acc] acc) ([acc x] (rf acc x)))) ; no init no complete rf

#_(defn by-key
  "Returns a transducer which partitions items according to kfn.
   It applies the transform specified by xform to each partition.
   Partitions contain the \"value part\" (as returned by vfn) of each item.
   The resulting transformed items are wrapped back into a \"pair\" using the pair function.
   Default values for kfn, vfn and pair are first, second (or identity if kfn is specified) and vector."
  ([xform] (by-key key' val' vector xform))
  ([kfn xform] (by-key kfn identity vector xform))
  ([kfn vfn xform] (by-key kfn vfn vector xform))
  ([kfn vfn pair xform]
    (fn [rf]
      (let [make-rf (if pair
                      (if-some [rf (when (identical? vector pair) (some-kvrf rf))]
                        (fn [k] (fn ([acc] acc) ([acc v] (rf acc k v))))
                        (fn [k] (fn ([acc] acc) ([acc v] (rf acc (pair k v))))))
                      (constantly (multiplexable rf)))
            m (volatile! (transient {}))]
        (if (and (= key' kfn) (= val' vfn))
          (kvrf self
            ([] (rf))
            ([acc] (rf (clj/reduce (fn [acc krf] (krf acc)) acc (vals (persistent! @m)))))
            ([acc x]
              (self acc (key' x) (val' x)))
            ([acc k v]
              (let [krf (or (get @m k) (doto (xform (make-rf k)) (->> (vswap! m assoc! k))))
                    acc (krf acc v)]
                 (when (reduced? acc) ; complete?
                   (vswap! m assoc! k noprf))
                 (unreduced acc))))
          (fn
            ([] (rf))
            ([acc] (rf (clj/reduce (fn [acc krf] (krf acc)) acc (vals (persistent! @m)))))
            ([acc x]
              (let [k (kfn x)
                    krf (or (get @m k) (doto (xform (make-rf k)) (->> (vswap! m assoc! k))))
                    acc (krf acc (vfn x))]
                (when (reduced? acc) ; complete?
                  (vswap! m assoc! k noprf))
                (unreduced acc)))))))))

#_(defn- spawn
  "Every n items, spawns a new pipeline."
  [n xform]
  (fn [rf]
    (let [mxrf (multiplexable rf)
          vrfs (volatile! [])
          m (volatile! 0)]
      (fn
        ([] (rf))
        ([acc]
          (rf (clj/reduce #(%2 %1) acc @vrfs)))
        ([acc x]
          (let [rfs @vrfs
                step! (fn [acc rf]
                        (let [acc (rf acc x)]
                          (if (reduced? acc)
                            (rf (unreduced acc))
                            (do
                              (vswap! vrfs conj! rf)
                              acc))))]
            (vreset! vrfs (transient []))
            (let [acc (clj/reduce step! acc rfs)
                  acc (if (neg? (vswap! m dec))
                        (do
                          (vswap! m + n)
                          (step! acc (xform mxrf)))
                        acc)]
              (vswap! vrfs persistent!)
              acc)))))))

#_(defn pad [n padding-coll]
  (fn [rf]
    (let [n (volatile! n)]
      (fn
        ([] (rf))
        ([acc]
          (rf (clj/reduce ((take @n) rf) acc padding-coll)))
        ([acc x]
          (vswap! n dec)
          (rf acc x))))))

#_(defn partition
  "Returns a partitioning transducer. Each partition is independently transformed using the xform transducer.
   Unlike clojure.core/partition the last partitions may be incomplete.
   Partitions can be padded using #'pad."
  ; being strict towards partition size implies buffering and avoiding unecessary buffering is part of this
  ; library goal. So partition won't support it. However a buffer transducer may be an option.
  ([n xform]
    (partition n n xform))
  ([n step xform]
    (spawn step (comp (take n) xform))))

#_(defn avg
  "Reducing fn to compute the arithmetic mean."
  ([]
    (let [count (volatile! 0)
          sum (volatile! 0)]
      (fn secret-container
        ([] (when (pos? @count) (/ @sum @count)))
        ([n]
          (vswap! count inc)
          (vswap! sum + n)
          secret-container))))
  ([acc] (acc))
  ([acc x] (acc x)))

#_(defn window
  "Returns a transducer which computes an accumulator over the last n items
   using two functions: f and its inverse invf.

   The accumulator is initialized with (f).
   It is updated to (f (invf acc out) in) where \"acc\" is the current value,
   \"in\" the new item entering the window, \"out\" the item exiting the window.
   The value passed to the dowstream reducing function is (f acc) enabling acc to be
   mutable and 1-arity f to project its state to a value.

   If you don't want to see the accumulator until the window is full then you need to
   use (drop (dec n)) to remove them."
  [n f invf]
  (fn [rf]
    (let [ring (object-array n)
          vi (volatile! (- n))
          vwacc (volatile! (f))]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc x]
          (let [i @vi
                wacc @vwacc] ; window accumulator
            (if (neg? i) ; not full yet
              (do
                (aset ring (+ n i) x)
                (vreset! vi (inc i))
                (rf acc (f (vreset! vwacc (f wacc x)))))
              (let [x' (aget ring i)]
                (aset ring i x)
                (vreset! vi (let [i (inc i)] (if (= n i) 0 i)))
                (rf acc (f (vreset! vwacc (f (invf wacc x') x))))))))))))

#_(defn count ([] 0) ([n] n) ([n _] (inc n)))

#_(defn juxt
  "Returns a reducing fn which compute all rfns at once and whose final return
   value is a vector of the final return values of each rfns."
  [& rfns]
  (let [rfns (vec rfns)]
    (fn
      ([] (mapv #(vector % (volatile! (%))) rfns))
      ([acc] (mapv (fn [[rf vacc]] (rf (unreduced @vacc))) acc))
      ([acc x]
        (let [some-unreduced (clj/reduce (fn [some-unreduced [rf vacc]]
                                           (when-not (reduced? @vacc) (vswap! vacc rf x) true))
                               false acc)]
          (if some-unreduced acc (reduced acc)))))))

#_(defn juxt-map
  [& key-rfns]
  (let [f (apply juxt (take-nth 2 (next key-rfns)))
        keys (vec (take-nth 2 key-rfns))]
    (fn
      ([] (f))
      ([acc] (zipmap keys (f acc)))
      ([acc x] (f acc x)))))

#_(defn first
  "Reducing function that returns the first value or nil if none."
  ([] nil)
  ([x] x)
  ([_ x] (reduced x)))

#_(defn transjuxt
  "Performs several transductions over coll at once. xforms-map can be a map or vector.
   Returns a map with the same keyset as xforms-map (or a vector of same size as xforms-map).
   Returns a transducer when coll is omitted."
  ([xforms-map]
    (let [[f args] (if (vector? xforms-map)
                     [juxt (map #(% first))]
                     [juxt-map (comp (by-key (map #(% first))) cat)])]
      (fn [rf]
        ((reduce (apply f (sequence args xforms-map))) rf))))
  ([xforms-map coll]
    (transduce (transjuxt xforms-map) first coll)))
