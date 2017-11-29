(ns argumentica.btree
  (:require [flow-gl.tools.trace :as trace]
            [flow-gl.debug :as debug]
            (argumentica [match :as match]
                         [cryptography :as cryptography]
                         [encode :as encode]
                         [graph :as graph]
                         [zip :as zip])
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as generators]
            [clojure.test.check.properties :as properties]
            [clojure.java.io :as io]
            [clojure.data.priority-map :as priority-map]
            [schema.core :as schema])
  (:use [clojure.test])
  (:import [java.io DataInputStream DataOutputStream]
           
           [java.nio.file.attribute FileAttribute]))

(defn create-node []
  {:values (sorted-set)})

(defn full-after-maximum-number-of-values [maximum]
  (assert (odd? maximum)
          "Maximum node size must be odd")
  (fn [node]
    (= maximum
       (count (:values node)))))

(defn create
  ([]
   (create (full-after-maximum-number-of-values 100)
           {}))

  ([full?]
   (create full?
           {}))
  
  ([full? storage]
   {:nodes {0 (create-node)}
    :next-node-id 1
    :loaded-nodes 1
    :root-id 0
    :full? full?
    :storage storage
    :usages (priority-map/priority-map)})

  ([full? storage root-storage-key]
   {:nodes {}
    :next-node-id 0
    :loaded-nodes 0
    :root-id root-storage-key
    :full? full?
    :storage storage
    :usages (priority-map/priority-map)}))

(defmulti get-from-storage
  (fn [storage key]
    (type storage)))

(defmethod get-from-storage
  clojure.lang.PersistentHashMap
  [storage key]
  (get storage
       key))

(defmethod get-from-storage
  clojure.lang.PersistentArrayMap
  [storage key]
  (get storage
       key))

(defmulti put-to-storage
  (fn [storage key value]
    (type storage)))

(defmethod put-to-storage
  clojure.lang.PersistentHashMap
  [storage key value]
  (assoc storage
         key
         value))

(defmethod put-to-storage
  clojure.lang.PersistentArrayMap
  [storage key value]
  (assoc storage
         key
         value))

(defmulti storage-keys
  (fn [storage]
    (type storage)))

(defmethod storage-keys
  clojure.lang.PersistentHashMap
  [storage]
  (keys storage))

(defmethod storage-keys
  clojure.lang.PersistentArrayMap
  [storage]
  (keys storage))

(defmulti remove-from-storage
  (fn [storage key]
    (type storage)))

(defmethod remove-from-storage
  clojure.lang.PersistentHashMap
  [storage key]
  (dissoc storage key))

(defmethod remove-from-storage
  clojure.lang.PersistentArrayMap
  [storage key]
  (dissoc storage key))

(defn unused-storage-keys [btree]
  (let [used-keys (into #{}
                        (keys (:storage-metadata btree)))]
    (filter (complement used-keys)
            (storage-keys (:storage btree)))))

(defn collect-storage-garbage [btree]
  (update btree :storage
          (fn [storage]
            (reduce remove-from-storage
                    storage
                    (unused-storage-keys btree)))))

(defn loaded? [node-id]
  (number? node-id))

(defn storage-key? [node-id]
  (string? node-id))

(defn median-value [values]
  (get values
       (int (/ (count values)
               2))))

(deftest test-median-value
  (is (= 2
         (median-value [1 2 3])))

  (is (= 3
         (median-value [1 2 3 4 5]))))

(defn split-sorted-set [source-sorted-set]
  (let [median-value (median-value (vec source-sorted-set))]
    {:lesser-values (into (sorted-set)
                          (subseq source-sorted-set
                                  <
                                  median-value))
     :greater-values (into (sorted-set)
                           (subseq source-sorted-set
                                   >
                                   median-value))
     :median-value median-value}))

(deftest test-split-sorted-set
  (is (= {:lesser-values #{1 2}
          :greater-values #{4 5}
          :median-value 3}
         (split-sorted-set (sorted-set 1 2 3 4 5)))))

(defn distribute-children [btree old-node-id new-node-id]
  (if-let [child-ids (get-in btree [:nodes old-node-id :child-ids])]
    (let [[lesser-child-ids greater-child-ids] (partition (/ (count child-ids)
                                                             2)
                                                          child-ids)]
      (-> btree
          (assoc-in [:nodes old-node-id :child-ids] (vec lesser-child-ids))
          (assoc-in [:nodes new-node-id :child-ids] (vec greater-child-ids))))
    btree))

(defn insert-after [sequence old-value new-value]
  (loop [head []
         tail sequence]
    (if-let [value (first tail)]
      (if (= value old-value)
        (concat head (list value new-value) (rest tail))
        (recur (conj head value)
               (rest tail)))
      (conj head
            new-value))))


(deftest test-insert-after
  (is (= '(1 2 3 4)
         (insert-after '(1 2 4)
                       2
                       3)))

  (is (= '(0 2 3 4)
         (insert-after '(0 2 3)
                       3
                       4))))

(defn record-usage [btree node-id]
  (-> btree 
      (update :usages
              assoc node-id (or (:next-usage-number btree)
                               0))
      (update :next-usage-number
              (fnil inc 0))))

(defn split-child [btree parent-id old-child-id]
  (let [{:keys [lesser-values greater-values median-value]} (split-sorted-set (get-in btree [:nodes old-child-id :values]))
        new-child-id (:next-node-id btree)]
    (-> btree
        (update :next-node-id inc)
        (update :loaded-nodes inc)
        (record-usage new-child-id)
        (assoc-in [:nodes old-child-id :values] lesser-values)
        (update-in [:nodes parent-id :values] conj median-value)
        (update-in [:nodes parent-id :child-ids] (fn [child-ids]
                                                   (vec (insert-after child-ids
                                                                      old-child-id
                                                                      new-child-id))) )
        (assoc-in [:nodes new-child-id] {:values greater-values})
        (distribute-children old-child-id new-child-id))))

(deftest test-split-child
  (is (= {:nodes {0 {:values #{1}},
                  1 {:values #{2 3}, :child-ids '(0 3 2)},
                  2 {:values #{4 5}},
                  3 {:values #{3}}},
          :loaded-nodes 4
          :next-node-id 4,
          :root-id 1
          :usages {3 0},
          :next-usage-number 1}
         (split-child {:nodes {0 {:values (sorted-set 1 2 3)},
                               1 {:values (sorted-set 3), :child-ids [0 2]},
                               2 {:values (sorted-set 4 5)}},
                       :loaded-nodes 3
                       :next-node-id 3,
                       :root-id 1}
                      1
                      0)))

  (is (= {:nodes
          {0 {:values #{0}},
           7 {:values #{8}},
           1 {:values #{1}, :child-ids [0 2]},
           4 {:values #{6}},
           6 {:values #{5}, :child-ids [3 4]},
           3 {:values #{4}},
           2 {:values #{2}},
           9 {:values #{9}, :child-ids [7 8]},
           5 {:values #{3 7}, :child-ids [1 6 9]},
           8 {:values #{10 11}}},
          :next-node-id 10,
          :loaded-nodes 10
          :root-id 5
          :usages {9 0},
          :next-usage-number 1}
         (split-child {:nodes {0 {:values (sorted-set 0)}
                               1 {:values (sorted-set 1), :child-ids [0 2]}
                               2 {:values (sorted-set 2)}
                               3 {:values (sorted-set 4)}
                               4 {:values (sorted-set 6)}
                               5 {:values (sorted-set 3), :child-ids [1 6]}
                               6 {:values (sorted-set 5 7 9), :child-ids [3 4 7 8]}
                               7 {:values (sorted-set 8)}
                               8 {:values (sorted-set 10 11)}}
                       :next-node-id 9,
                       :loaded-nodes 9
                       :root-id 5}
                      5
                      6))))

(defn add-root-to-usages [usages root-id]
  (apply priority-map/priority-map 
         (mapcat (fn [[cursor priority]]
                   [(concat [root-id]
                            cursor)
                    priority])
                 usages)))

(deftest test-add-root-to-usages
  (is (= {'(1 2 3) 1, '(1 3 4) 2}
         (add-root-to-usages (priority-map/priority-map [2 3] 1
                                                        [3 4] 2)
                             1))))



(defn split-root [btree]
  (let [new-root-id (:next-node-id btree)]
    (-> btree
        (update :next-node-id inc)
        (assoc :root-id new-root-id)
        (assoc-in [:nodes new-root-id] {:child-ids [(:root-id btree)]
                                        :values (sorted-set)})
        (update :loaded-nodes inc)
        (record-usage new-root-id)
        #_(update :usages add-root-to-usages new-root-id)
        (split-child new-root-id (:root-id btree)))))

(deftest test-split-root
  (is (= {:nodes
          {0 {:values #{1 2}},
           1 {:values #{3}, :child-ids [0 2]},
           2 {:values #{4 5}}},
          :next-node-id 3,
          :loaded-nodes 3
          :usages {0 0, 1 1, 2 2},
          :next-usage-number 3
          :root-id 1}
         (split-root {:nodes {0 {:values (sorted-set 1 2 3 4 5)}}
                      :loaded-nodes 1
                      :next-node-id 1
                      :next-usage-number 1
                      :usages (priority-map/priority-map 0 0)
                      :root-id 0})))

  (is (= {:nodes {0 {:values (sorted-set 0)}
                  1 {:values (sorted-set 1)
                     :child-ids [0 2]}
                  2 {:values (sorted-set 2)}
                  3 {:values (sorted-set 4)}
                  4 {:values (sorted-set 6 7)}
                  5 {:values (sorted-set 3)
                     :child-ids [1 6]}
                  6 {:values (sorted-set 5)
                     :child-ids [3 4]}},
          :loaded-nodes 7
          :next-node-id 7,
          :usages {0 0, 1 0, 4 0, 3 0, 2 0, 5 0, 6 1}
          :root-id 5
          :next-usage-number 2}
         (split-root {:nodes {0 {:values (sorted-set 0)}
                              1 {:values (sorted-set 1 3 5), :child-ids [0 2 3 4]}
                              2 {:values (sorted-set 2)}
                              3 {:values (sorted-set 4)}
                              4 {:values (sorted-set 6 7)}},
                      :usages (priority-map/priority-map 0 0
                                                         1 0
                                                         2 0
                                                         3 0
                                                         4 0)
                      :loaded-nodes 5
                      :next-node-id 5,
                      :root-id 1}))))



(defn child-index [splitter-values value]
  (loop [splitter-values splitter-values
         child-index 0]
    (if-let [splitter-value (first splitter-values)]
      (if (= splitter-value
             value)
        nil
        (if (neg? (compare value
                           splitter-value))
          child-index
          (recur (rest splitter-values)
                 (inc child-index))))
      child-index)))

(deftest test-child-index
  (is (= 0
         (child-index [2 4] 1)))
  (is (= 1
         (child-index [2 4] 3)))
  (is (= 2
         (child-index [2 4] 5)))
  (is (= nil
         (child-index [2 4] 2))))

(defn child-id [parent-node value]
  (if-let [the-child-index (child-index (:values parent-node)
                                        value)]
    (nth (:child-ids parent-node)
         the-child-index)
    nil))

(deftest test-child-id
  (is (= 3
         (child-id {:values #{-1 3}, :child-ids '(0 3 2)}
                   2))))

(defn find-index [sequence value]
  (first (keep-indexed (fn [index sequence-value]
                         (if (= value sequence-value)
                           index
                           nil))
                       sequence)))

(deftest test-find-index
  (is (= 2
         (find-index [1 2 3] 3)))
  (is (= nil
         (find-index [1 2 3] 4))))

(defn leaf-node? [node]
  (not (:child-ids node)))

(defn node [btree node-id]
  (get-in btree [:nodes node-id]))



(defn add [btree value]

  (loop [btree (if ((:full? btree) (node btree
                                         (:root-id btree)))
                 (split-root btree)
                 btree)
         node-id (:root-id btree)
         cursor [node-id]]
    (assert (loaded? node-id)
            "Nodes needed for addition must be loaded. Use add-to-atom to load requried nodes and add a new value.")  
    (let [the-node (node btree
                         node-id)]
      (if (leaf-node? the-node)
        (-> btree
            (update-in [:nodes node-id :values]
                       conj value)
            (record-usage node-id))
        (if-let [the-child-id (child-id the-node
                                        value)]
          (let [btree (if ((:full? btree) (node btree
                                                the-child-id))
                        (split-child btree
                                     node-id
                                     the-child-id)
                        btree)]
            (if-let [the-child-id (child-id (node btree
                                                  node-id)
                                            value)]
              (recur btree
                     the-child-id
                     (conj cursor
                           the-child-id))
              btree))
          btree)))))

(deftest test-add
  (let [full? (fn [node]
                (= 5 (count (:values node))))]

    (testing "root is full"
      (is (= {0 {:values #{1 2}},
              1 {:values #{3}, :child-ids [0 2]},
              2 {:values #{4 5 6}}}
             (:nodes (add {:nodes {0 {:values (sorted-set 1 2 3 4 5)}}
                           :loaded-nodes 1
                           :next-node-id 1
                           :root-id 0
                           :full? full?}
                          6)))))

    (testing "no splits needed"
      (is (= {:nodes
              {0 {:values (sorted-set -1 1 2)},
               1 {:values (sorted-set 3), :child-ids [0 2]},
               2 {:values (sorted-set 4 5 6)}},
              :next-node-id 3,
              :root-id 1,
              :full? full?
              :next-usage-number 1
              :usages {0 0}}
             (add {:nodes
                   {0 {:values (sorted-set 1 2)},
                    1 {:values (sorted-set 3), :child-ids [0 2]},
                    2 {:values (sorted-set 4 5 6)}},
                   :next-node-id 3,
                   :root-id 1,
                   :full? full?}
                  -1))))

    (testing "leaf is full"
      (is (match/contains-map? {:nodes
                                 {0 {:values #{-3 -2}},
                                  1 {:values #{-1 3}, :child-ids '(0 3 2)},
                                  2 {:values #{4 5 6}},
                                  3 {:values #{0 1 2}}},
                                 :next-node-id 4,
                                 :loaded-nodes 4
                                 :root-id 1,
                                 :usages {3 4},
                                 :next-usage-number 5}
                                (add {:nodes
                                      {0 {:values (sorted-set -3 -2 -1 0 1)},
                                       1 {:values (sorted-set 3), :child-ids [0 2]},
                                       2 {:values (sorted-set 4 5 6)}},
                                      :next-usage-number 3
                                      :next-node-id 3,
                                      :loaded-nodes 3
                                      :root-id 1,
                                      :full? full?}
                                     2))))


    (is (= {0 {:values #{0}},
            1 {:child-ids [0 2], :values #{1}},
            2 {:values #{2}},
            3 {:values #{4}},
            4 {:values #{6}},
            5 {:child-ids [1 6], :values #{3}},
            6 {:values #{5 7}, :child-ids [3 4 7]},
            7 {:values #{8 9}}}
           (:nodes (reduce add
                           (create (full-after-maximum-number-of-values 3))
                           (range 10)))))))





(defn parent-id [cursor]
  (last (drop-last cursor)))

(defn next-cursor [btree cursor]
  (loop [cursor cursor]
    (if-let [parent (node btree
                          (parent-id cursor))]
      (if-let [next-node-id-downwards (get (:child-ids parent)
                                           (inc (find-index (:child-ids parent)
                                                            (last cursor))))]
        (loop [cursor (conj (vec (drop-last cursor))
                            next-node-id-downwards)]
          (let [next-node-downwards (node btree
                                          (last cursor))]
            (if (leaf-node? next-node-downwards)
              cursor
              (recur (conj cursor
                           (first (:child-ids next-node-downwards)))))))
        
        (recur (drop-last cursor)))
      nil)))

(deftest test-next-cursor
  (is (= [1 2]
         (next-cursor {:nodes
                       {1 {:child-ids [0 2]}}}
                      [1 0])))

  (is (= nil
         (next-cursor {:nodes
                       {1 {:child-ids [0 2]}}}
                      [1 2])))


  (is (= [1 2]
         (next-cursor {:nodes {1 {:child-ids [0 2 3]}}
                       :next-node-id 4
                       :root-id 1}
                      [1 0])))

  (is (= [5 1 2]
         (next-cursor {:nodes {1 {:child-ids [0 2]}
                               5 {:child-ids [1 6]}
                               6 {:child-ids [3 4]}},
                       :next-node-id 7
                       :root-id 5}
                      [5 1 0])))

  (is (= [5 6 3]
         (next-cursor {:nodes {1 {:child-ids [0 2]}
                               5 {:child-ids [1 6]}
                               6 {:child-ids [3 4]}},
                       :next-node-id 7
                       :root-id 5}
                      [5 1 2]))))



(defn splitter-after-child [node child-id]
  ;; TODO: This is linear time. Could we find the last value in
  ;; the child node and then find the splitter both in logarithmic time?
  (nth (seq (:values node))
       (find-index (:child-ids node)
                   child-id)
       nil))

(deftest test-splitter-after-child
  (is (= 3
         (splitter-after-child {:values (sorted-set 3)
                                :child-ids [0 2]}
                               0)))
  (is (= nil
         (splitter-after-child {:values (sorted-set 3)
                                :child-ids [0 2]}
                               2)))

  (is (= nil
         (splitter-after-child {:values (sorted-set 3)
                                :child-ids [0 2]}
                               2))))



(defn drop-until-equal [sequence value]
  (drop-while #(not= % value)
              sequence))


(defn children-after [parent child-id]
  (rest (drop-until-equal (:child-ids parent)
                          child-id)))

(defn splitter-after-cursor [btree cursor]
  (loop [cursor cursor]
    (if-let [parent (node btree
                          (last (drop-last cursor)))]
      (if (empty? (children-after parent
                                  (last cursor)))
        (recur (drop-last cursor))
        (splitter-after-child parent
                              (last cursor)))
      nil)))


(deftest test-splitter-after-cursor
  (let [btree {:nodes {0 {:values (sorted-set 0)}
                       1 {:values (sorted-set 1)
                          :child-ids [0 2]}
                       2 {:values (sorted-set 2)}
                       3 {:values (sorted-set 4)}
                       4 {:values (sorted-set 6 7 8)}
                       5 {:values (sorted-set 3)
                          :child-ids [1 6]}
                       6 {:values (sorted-set 5)
                          :child-ids [3 4]}},
               :next-node-id 7,
               :root-id 5}]
    (is (= 3
           (splitter-after-cursor btree
                                  [5 1 2])))

    (is (= nil
           (splitter-after-cursor btree
                                  [5 6 4])))

    (is (= 5
           (splitter-after-cursor btree
                                  [5 6 3]))))


  (let [btree {:nodes
               {0 {:values (sorted-set 1 2)},
                1 {:values (sorted-set 3)
                   :child-ids [0 2]},
                2 {:values (sorted-set 4 5 6)}}
               :root-id 1}]

    (is (= 3
           (splitter-after-cursor btree
                                  [1 0])))

    (is (= nil
           (splitter-after-cursor btree
                                  [1 2])))))

(defn append-if-not-null [collection value]
  (if value
    (concat collection
            [value])
    collection))

(defn sequence-for-cursor [btree cursor]
  (append-if-not-null (seq (:values (node btree
                                          (last cursor))))
                      (splitter-after-cursor btree
                                             cursor)))

(deftest test-sequence-for-cursor
  (is (= [1 2 3]
         (sequence-for-cursor {:nodes
                               {0 {:values (sorted-set 1 2)},
                                1 {:values (sorted-set 3), :child-ids [0 2]},
                                2 {:values (sorted-set 4 5 6)}}}
                              [1 0])))
  (is (= [4 5 6]
         (sequence-for-cursor {:nodes
                               {0 {:values (sorted-set 1 2)},
                                1 {:values (sorted-set 3), :child-ids [0 2]},
                                2 {:values (sorted-set 4 5 6)}}}
                              [1 2]))))

(defn first-cursor [btree]
  (loop [cursor [(:root-id btree)]
         node-id (:root-id btree)]
    (let [the-node (node btree
                         node-id)]
      (if (leaf-node? the-node)
        cursor
        (let [first-child-id (first (:child-ids the-node))]
          (recur (conj cursor
                       first-child-id)
                 first-child-id))))))

(deftest test-first-cursor
  (is (= [1 0]
         (first-cursor {:nodes {0 {:values (sorted-set 1 2)},
                                1 {:values (sorted-set 3), :child-ids [0 2]},
                                2 {:values (sorted-set 4 5 6)}}
                        :root-id 1}))))


(defn replace-node-id [btree parent-id old-child-id new-child-id]
  (if parent-id
    (update-in btree
               [:nodes parent-id :child-ids]
               (partial replace
                        {old-child-id new-child-id}))
    (assoc btree :root-id new-child-id)))

(defn node-to-bytes [node]
  (zip/compress-byte-array (.getBytes (pr-str node)
                                      "UTF-8"))
  #_(let [zip-output-stream (zip/byte-array-output-stream)]
    
    (zip/write-zip-file-with-one-entry (zip/string-input-stream (prn-str node)
                                                                "UTF-8")
                                       zip-output-stream)

    (.toByteArray zip-output-stream)))

(comment
  (node-to-bytes {:a :b})
  (storage-key (node-to-bytes {:a :b}))
  (String. (zip/read-first-entry-from-zip (zip/byte-array-input-stream (node-to-bytes {:a :b})))
           "UTF-8"))

(defn bytes-to-node [bytes]
  (-> (binding [*read-eval* false]
        (try 
          (read-string (String. (zip/uncompress-byte-array bytes)
                                "UTF-8"))
          (catch Exception e
            (prn (String. (zip/uncompress-byte-array bytes)
                          "UTF-8"))
            (throw e))))
      (update :values
              (fn [values]
                (into (sorted-set)
                      values)))))

(defn storage-key [bytes]
  (encode/base-16-encode (cryptography/sha-256 bytes)))

(deftest test-node-serialization
  (let [node (create-node)]
    (is (= node
           (bytes-to-node (node-to-bytes node))))))


(defn unload-cursor [btree cursor]
  (loop [cursor cursor
         btree btree]
    (let [node-id-to-be-unloded (last cursor)
          parent-id (last (drop-last cursor))
          the-node (node btree
                         node-id-to-be-unloded)
          bytes (node-to-bytes the-node)
          the-storage-key (storage-key bytes)
          btree (-> btree
                    (replace-node-id parent-id
                                     node-id-to-be-unloded
                                     the-storage-key)
                    (update :nodes dissoc node-id-to-be-unloded)
                    (update :loaded-nodes dec)
                    (update :usages dissoc node-id-to-be-unloded)
                    (update :storage-metadata 
                            assoc
                            the-storage-key
                            (conj (select-keys the-node [:child-ids])
                                  {:value-count (count (:values the-node))
                                   :storage-byte-count (count bytes)}))
                    (update :storage
                            put-to-storage
                            the-storage-key
                            bytes))]
      (if (and parent-id
               (every? (complement loaded?)
                       (:child-ids (node btree
                                         parent-id))))
        (recur (drop-last cursor)
               btree)
        btree))))

(deftest test-unload-cursor
  (is (= {1 {:values #{3},
             :child-ids [match/any-string
                         2]},
          2 {:values #{4 5 6}}}
         (:nodes (unload-cursor {:nodes {0 {:values (sorted-set 1 2)},
                                         1 {:values (sorted-set 3), :child-ids [0 2]},
                                         2 {:values (sorted-set 4 5 6)}}
                                 :loaded-nodes 3
                                 :root-id 1
                                 :storage {}}
                                [1 0]))))

  (is (= {:nodes {},
          :root-id match/any-string
          :loaded-nodes 0}
         (-> (unload-cursor {:nodes
                             {1 {:values #{3},
                                 :child-ids ["B58E78A458A49C835829351A3853B584CA01124A1A96EB782BA23513124F01A7"
                                             2]},
                              2 {:values #{4 5 6}}},
                             :loaded-nodes 2
                             :storage {}
                             :root-id 1}
                            [1 2])
             (select-keys [:nodes :root-id :loaded-nodes])))))

(defn cursors [cursor btree]
  (if-let [the-next-cursor (next-cursor btree
                                        cursor)]
    (lazy-seq (cons cursor
                    (cursors (next-cursor btree
                                          cursor)
                             btree)))
    [cursor]))

(deftest test-cursors
  (is (= '([1 0]
           [1 2])
         (let [btree {:nodes {0 {:values (sorted-set 1 2)},
                              1 {:values (sorted-set 3), :child-ids [0 2]},
                              2 {:values (sorted-set 4 5 6)}}
                      :root-id 1}]
           (cursors (first-cursor btree)
                    btree))))
  (is (= [[13 5 1 0]
          [13 5 1 2]
          [13 5 match/any-string]
          [13 14 9 7]
          [13 14 9 8]
          [13 14 12 10]
          [13 14 12 11]
          [13 14 12 15]
          [13 14 12 16]]
         (let [btree (-> (reduce add
                                 (create (full-after-maximum-number-of-values 3))
                                 (range 20))
                         (unload-cursor [13 5 6 3])
                         (unload-cursor [13 5 6 4]))]
           (cursors [13 5 1 0]
                    btree)))))

(defn get-node-content [storage storage-key]
  (bytes-to-node (get-from-storage storage
                                   storage-key)))

(defn set-node-content [btree parent-id storage-key node-content]
  (-> btree
      (assoc-in [:nodes (:next-node-id btree)]
                node-content)
      (replace-node-id parent-id
                       storage-key
                       (:next-node-id btree))
      (update :next-node-id
              inc)

      (update :loaded-nodes
              (fnil inc 0))
      (update :storage-metadata 
              dissoc storage-key)))

(declare unload-btree)

(deftest test-set-node-content
  (let [full? (full-after-maximum-number-of-values 3)
        btree (unload-btree (reduce add
                                    (create full?)
                                    (range 10)))]
    (is (= {8 {:child-ids
               [match/any-string
                match/any-string],
               :values #{3}}}
           (:nodes (set-node-content btree
                                     nil
                                     (:root-id btree)
                                     (get-node-content (:storage btree)
                                                       (:root-id btree))))))

    (is (= {8 {:child-ids
             [9 "E33374D7B0AEF7964CBA2A2A4B48BF1DFFB7B3F2F38782959C5D3C1D3EA8D444"],
             :values #{3}},
            9 "abc"}
           
           (:nodes (set-node-content {:nodes {8
                                              {:child-ids
                                               ["43B0199869DFD7D8B392D4F217CFB9E57D0CB52ABB4246EB60304E81AA7999B7"
                                                "E33374D7B0AEF7964CBA2A2A4B48BF1DFFB7B3F2F38782959C5D3C1D3EA8D444"],
                                               :values #{3}}},
                                      :next-node-id 9,
                                      :root-id 8,
                                      :full? full?
                                      :storage (:storage btree)}
                                     8
                                     "43B0199869DFD7D8B392D4F217CFB9E57D0CB52ABB4246EB60304E81AA7999B7"
                                     "abc"
                                     #_(get-node-content (:storage btree)
                                                         "43B0199869DFD7D8B392D4F217CFB9E57D0CB52ABB4246EB60304E81AA7999B7")))))))


(defn load-node [btree-atom parent-id storage-key]
  (swap! btree-atom
         set-node-content
         parent-id
         storage-key
         (get-node-content (:storage @btree-atom)
                           storage-key)))



(defn cursor-and-btree-for-value [btree-atom value]
  (loop [btree @btree-atom
         cursor [(:root-id btree)]
         node-id (:root-id btree)]
    (if (storage-key? node-id)
      (do (load-node btree-atom
                     (parent-id cursor)
                     node-id)
          (let [btree @btree-atom]
            (recur btree
                   [(:root-id btree)]
                   (:root-id btree))))
      (let [the-node (node btree
                           node-id)]
        (if (leaf-node? the-node)
          {:btree btree
           :cursor cursor}
          (if-let [the-child-id (child-id the-node
                                          value)]
            (recur btree
                   (conj cursor the-child-id)
                   the-child-id)
            {:btree btree
             :cursor cursor}))))))


(deftest test-cursor-and-btree-for-value
  (let [btree-atom (atom {:nodes
                          {0 {:values (sorted-set 1 2)},
                           1 {:values (sorted-set 3)
                              :child-ids [0 2]},
                           2 {:values (sorted-set 4 5 6)}}
                          :loaded-nodes 3
                          :root-id 1
                          :storage {}
                          :usages (priority-map/priority-map)
                          :next-node-id 3})]
    (swap! btree-atom
           unload-btree)

    (is (match/contains-map? {:nodes {3 {:values #{3},
                                         :child-ids [4
                                                     match/any-string]},
                                      4 {:values #{1 2}}},
                              :root-id 3,
                              :loaded-nodes 2
                              :usages {}
                              :next-node-id 5}
                             (dissoc (:btree (cursor-and-btree-for-value btree-atom
                                                                         1))
                                     :storage)))

    (is (= [3 4]
           (:cursor (cursor-and-btree-for-value btree-atom
                                                1))))

    (is (= [3]
           (:cursor (cursor-and-btree-for-value btree-atom
                                                3))))))


#_(defn leaf-node-cursors-least-used-first [btree]
  (filter (fn [cursor]
            (leaf-node? (node btree
                              (last cursor))))
          (map first (:usages btree))))


#_(deftest test-leaf-node-cursors-least-used-first
  (is (= '((5 1 0) (5 1 2) (5 1 3) (5 1 4) [5 6 4] [5 6 7])
         (leaf-node-cursors-least-used-first (reduce add
                                                     (create (full-after-maximum-number-of-values 3))
                                                     (range 10))))))

(comment (map first (:usages (reduce add
                                     (create (full-after-maximum-number-of-values 3))
                                     (range 10))))
         (node (reduce add
                  (create (full-after-maximum-number-of-values 3))
                  (range 10))
               0))

(defn least-used-cursor [btree]
  (loop [node-id (:root-id btree)
         cursor [node-id]]
    (let [the-node (node btree
                         node-id)]
      (if (leaf-node? the-node)
        cursor
        (let [least-used-child-id (first (sort-by (:usages btree)
                                                  (filter loaded?
                                                          (:child-ids the-node))))]
          
          (recur least-used-child-id
                 (conj cursor
                       least-used-child-id)))))))


(deftest test-least-used-cursor
  (is (= [5 1 0]
         (least-used-cursor {:nodes
                             {0 {:values #{0}},
                              1 {:child-ids [0 2], :values #{1}},
                              2 {:values #{2}},
                              3 {:values #{4}},
                              4 {:values #{6}},
                              5 {:child-ids [1 6], :values #{3}},
                              6 {:values #{5 7}, :child-ids [3 4 7]},
                              7 {:values #{8 9}}},
                             :root-id 5,
                             :usages {0 2, 2 4, 3 6, 4 8, 7 9}}))))

(defn unload-excess-nodes [btree maximum-node-count]
  #_(prn "unload" maximum-node-count
       (:loaded-nodes btree))
  (loop [btree btree]
    (if (< maximum-node-count
           (:loaded-nodes btree))
      (recur (unload-cursor btree
                            (least-used-cursor btree)))
      btree)))


(comment
  (String. (byte-array [123, 58, 118, 97, 108, 117, 101, 115, 32, 35, 123, 49, 52, 125,
                        125, 10]))

  (IsFunction.)

  (schema/check {:a (schema/eq :b)}
                {:a :b}))


(deftest test-unload-excess-nodes
  (is (match/contains-map? {:nodes
                            {5 {:child-ids [match/any-string
                                            6],
                                :values #{3}},
                             6 {:values #{5 7},
                                :child-ids [match/any-string
                                            match/any-string
                                            7]},
                             7 {:values #{8 9}}},
                            :next-node-id 8,
                            :loaded-nodes 3,
                            :root-id 5,
                            :usages {5 12, 6 13, 7 16},
                            :next-usage-number 17}
                           (unload-excess-nodes (reduce add
                                                        (create (full-after-maximum-number-of-values 3))
                                                        (range 10))
                                                3)))


  (is (= 5
         (count (keys (:nodes (unload-excess-nodes (reduce add
                                                           (create (full-after-maximum-number-of-values 3))
                                                           (range 30))
                                                   5)))))))


(defn add-to-atom
  "Adds a value to an btree atom. Loads nodes from storage as needed.
  WARNING: Overrides any concurrent modifications to the atom."
  [btree-atom value]
  (reset! btree-atom
          (add (:btree (cursor-and-btree-for-value btree-atom
                                                   value))
               value)))


(deftest test-add-to-atom
  (let [full? (fn [node]
                (= 5 (count (:values node))))

        run-test (fn [btree value nodes-after-addition]
                   (let [btree-atom (atom btree)]
                     (swap! btree-atom
                            unload-btree)

                     (add-to-atom btree-atom
                                  value)

                     (is (= nodes-after-addition
                            (:nodes @btree-atom)))))]

    (testing "root is full"
      (run-test {:nodes {0 {:values (sorted-set 1 2 3 4 5)}}
                 :next-node-id 1
                 :loaded-nodes 1
                 :root-id 0
                 :storage {}
                 :full? full?}
                
                6
                
                {1 {:values #{1 2}},
                 2 {:child-ids [1 3], :values #{3}},
                 3 {:values #{4 5 6}}}))

    (testing "no splits needed"
      (run-test {:nodes {0 {:values (sorted-set 1 2)},
                         1 {:values (sorted-set 3), :child-ids [0 2]},
                         2 {:values (sorted-set 4 5 6)}},
                 :loaded-nodes 3
                 :next-node-id 3,
                 :root-id 1,
                 :storage {}
                 :full? full?}
                
                -1
                
                {3 {:values #{3},
                    :child-ids [4
                                match/any-string]},
                 4 {:values #{-1 1 2}}}))

    (testing "leaf is full"
      (run-test {:nodes
                 {0 {:values (sorted-set -3 -2 -1 0 1)},
                  1 {:values (sorted-set 3), :child-ids [0 2]},
                  2 {:values (sorted-set 4 5 6)}},
                 :loaded-nodes 3
                 :next-node-id 3,
                 :root-id 1,
                 :storage {}
                 :full? full?}

                2

                {3 {:values #{-1 3},
                    :child-ids [4
                                5
                                match/any-string]},
                 4 {:values #{-3 -2}},
                 5 {:values #{0 1 2}}}))))

(defn btree-and-node-id-after-splitter [btree-atom splitter]
  (let [{:keys [cursor btree]} (cursor-and-btree-for-value btree-atom
                                                           splitter)

        node-with-the-splitter (node btree
                                     (last cursor))
        node-id-after-splitter (get (:child-ids node-with-the-splitter)
                                    (inc (find-index (:values node-with-the-splitter)
                                                     splitter)))]
    
    {:node-id-after-splitter node-id-after-splitter
     :btree btree
     :cursor (conj cursor
                   node-id-after-splitter)}))

(defn sequence-after-splitter [btree-atom splitter]

  (let [{:keys [node-id-after-splitter btree cursor]} (btree-and-node-id-after-splitter btree-atom
                                                                                        splitter)]

    (loop [btree btree
           cursor cursor
           node-id node-id-after-splitter]
      (if (storage-key? node-id)
        (do (load-node btree-atom
                       (parent-id cursor)
                       node-id)
            (let [{:keys [node-id-after-splitter btree cursor]} (btree-and-node-id-after-splitter btree-atom
                                                                                                  splitter)]
              (recur btree
                     cursor
                     node-id-after-splitter)))
        (let [the-node (node btree
                             node-id)]
          (if (leaf-node? the-node)
            (append-if-not-null (:values the-node)
                                (splitter-after-cursor btree
                                                       cursor))
            (let [the-child-id (first (:child-ids the-node))]
              (recur btree
                     (conj cursor the-child-id)
                     the-child-id))))))))

(deftest test-sequence-after-splitter
  (let [btree-atom (atom {:nodes {0 {:values (sorted-set 0)}
                                  1 {:values (sorted-set 1)
                                     :child-ids [0 2]}
                                  2 {:values (sorted-set 2)}
                                  3 {:values (sorted-set 4)}
                                  4 {:values (sorted-set 6 7 8)}
                                  5 {:values (sorted-set 3)
                                     :child-ids [1 6]}
                                  6 {:values (sorted-set 5)
                                     :child-ids [3 4]}},
                          :loaded-nodes 7
                          :next-node-id 7,
                          :root-id 5
                          :storage {}})]
    (swap! btree-atom
           unload-btree)

    (are [splitter sequence]
        (= sequence
           (sequence-after-splitter btree-atom
                                    splitter))

      3   [4 5]
      8   nil)))


(defn sequence-for-value [btree-atom value]
  (let [{:keys [cursor btree]} (cursor-and-btree-for-value btree-atom
                                                           value)]
    (let [the-node (node btree
                         (last cursor))]
      (if (leaf-node? the-node)
        (append-if-not-null (subseq (:values the-node)
                                    >=
                                    value)
                            (splitter-after-cursor btree
                                                   cursor))
        [value]))))

(deftest test-sequence-for-value
  (let [btree-atom (atom {:nodes
                          {0 {:values (sorted-set 1 2)},
                           1 {:values (sorted-set 3)
                              :child-ids [0 2]},
                           2 {:values (sorted-set 4 5 6)}}
                          :root-id 1
                          :loaded-nodes 3
                          :storage {}
                          :next-node-id 3})]
    (swap! btree-atom
           unload-btree)

    (are [value sequence]
        (= sequence
           (sequence-for-value btree-atom
                               value))

      0   [1 2 3]
      1   [1 2 3]
      3   [3]
      -10 [1 2 3]
      5   [5 6]
      50  nil)))

(defn lazy-value-sequence [btree-atom sequence]
  (if-let [sequence (if (first (rest sequence))
                      sequence
                      (if-let [value (first sequence)]
                        (cons value
                              (sequence-after-splitter btree-atom
                                                       (first sequence)))
                        nil))]
    (lazy-seq (cons (first sequence)
                    (lazy-value-sequence btree-atom
                                         (rest sequence))))
    nil))

(defn inclusive-subsequence [btree-atom value]
  (lazy-value-sequence btree-atom
                 (sequence-for-value btree-atom
                                     value)))

(deftest test-inclusive-subsequence
  (let [btree-atom (atom {:nodes
                          {0 {:values (sorted-set 1 2)},
                           1 {:values (sorted-set 3)
                              :child-ids [0 2]},
                           2 {:values (sorted-set 4 5 6)}}
                          :root-id 1
                          :loaded-nodes 3
                          :next-node-id 3
                          :storage {}})]

    (swap! btree-atom
           unload-btree)

    (is (= 3
           (count (keys (:storage-metadata @btree-atom)))))

    (is (= [1 2 3 4 5 6]
           (inclusive-subsequence btree-atom
                                  0)))

    (is (= 0
           (count (keys (:storage-metadata  @btree-atom)))))


    (is (= [2 3 4 5 6]
           (inclusive-subsequence btree-atom
                                  2)))

    (is (= [3 4 5 6]
           (inclusive-subsequence btree-atom
                                  3)))

    (is (= [4 5 6]
           (inclusive-subsequence btree-atom
                                  4)))

    (is (= nil
           (inclusive-subsequence btree-atom
                                  7)))

    (let [values (range 200)]
      (is (= values
             (inclusive-subsequence (atom (reduce add
                                                  (create (full-after-maximum-number-of-values 3))
                                                  values))
                                    (first values)))))))

(deftest test-btree
  (repeatedly 100 
              (let [maximum 1000
                    values (take 200
                                 (repeatedly (fn [] (rand-int maximum))))
                    smallest (rand maximum)]
                (is (= (subseq (apply sorted-set
                                      values)
                               >=
                               smallest)
                       (inclusive-subsequence (atom (reduce add
                                                            (create (full-after-maximum-number-of-values 3))
                                                            values))
                                              smallest))))))

(defn unload-btree [btree]
  (loop [btree btree
         cursors (filter (fn [cursor]
                           (loaded? (last cursor)))
                         (cursors (first-cursor btree)
                                  btree))]
    (if-let [cursor (first cursors)]
      (let [btree (unload-cursor btree
                                 cursor)]
        (recur btree
               (rest cursors)))
      btree)))


(deftest test-unload-btree
  (testing "unload btree when some nodes are unloaded in the middle"
    (is (= '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19)
           (inclusive-subsequence (atom (-> (reduce add
                                                    (create (full-after-maximum-number-of-values 3))
                                                    (range 20))
                                            (unload-cursor [13 5 6 3])
                                            (unload-cursor [13 5 6 4])
                                            (unload-btree)))
                                  0))))

  (testing "unload btree when first nodes are unloaded"
    (is (= '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19)
           (inclusive-subsequence (atom (-> (reduce add
                                                    (create (full-after-maximum-number-of-values 3))
                                                    (range 20))
                               
                                            (unload-cursor [13 5 1 0])
                                            (unload-cursor [13 5 1 2])
                                            (unload-btree)))
                                  0)))))

(deftest test-save-to-storage
  (let [full? (full-after-maximum-number-of-values 3)]
    (is {:nodes {},
         :next-node-id 8,
         :root-id "E8E43D7CC21CEAE35F88DF61E1846070DF17B13B8E683D3D9F592A324D640B92",
         :full? full?}
        (unload-btree (reduce add
                              (create full?)
                              (range 10))))))


(defn load-first-cursor [btree-atom]
  (loop [cursor (first-cursor @btree-atom)]
    (if (not (loaded? (last cursor)))
      (recur (first-cursor (load-node btree-atom
                                      (parent-id cursor)
                                      (last cursor)))))))



(defn storage-keys-from-stored-nodes [storage root-key]
  (tree-seq (fn [storage-key]
              (not (leaf-node? (get-node-content storage
                                                 storage-key))))
            (fn [storage-key]
              (:child-ids (get-node-content storage
                                            storage-key)))
            root-key))

(deftest test-storage-keys-from-stored-nodes
  (is (= '("F9BB95AB72D53E649CBBF11393513766328FEC2854368CDF711BE0D9A0F7E50E"
           "FC1BA555525A5BD07E5BF7F3A2F22D8DD9CC8F513E39933390A7CD5487E8B88D"
           "796AD3EEF52891C9000283476C05418E626F8DB78CB80177180D0A33831EFC8C"
           "C657A0DE1F4454290D14CAD2FC6472174DB247A474DC4AC7ED71F53D0669CE04"
           "61C88379F997BA90CA05A124B47C52FB60649D0A281EC1892F2482D3BFFC4FFE"
           "1552ED8E39B3FF5EFF43E9D33F8312274F898739862A72A46557E53EF163CA0F"
           "3383407D43C265C4D7E89F4AAE7AFFF0A7F34FB03480991ABD3552DF30F5780C"
           "42521C364179D339A1D113688F7BE4E72D8AC5D4E10C10987B441847E9DACE45"
           "3BE0B4C1B1B8AC80DE2624E516AEFC08E89B274BDCD332EFE687E244454EEADC"
           "07827984EDC3C50B0F822622D379F5910F3FBA61F876DAB141A9006DA9D5C52F"
           "E06A1920A38B07A5AA2096886C5F0E136DDCBF1A42C38CF2E895EECB769BD410"
           "07C6028CA1B9FD0A62A622EC3AFAF75FADB678E1873BA017F601E5C17908DFDC"
           "186882D1FB0CA8B418C7B8B0BB1111D155ECABA205B05DCE074C6E8506B3B007"
           "32F8379E24C819D47612AFCA7EEB6979D3DC58518BE5E818375EACCF589620FE"
           "BF1712D67656931C4CCC1AFFB6B9198A9A54389DDDD92F9650C088EBC6249EF9"
           "36BDF55B4AF483308E6FCB89F07844F10CF52914F667560A22BF3CDFCD877AFB"
           "5252BDB24DC15231B0F868D3EEB70E094AC5A95E4382E3C3CC6D469CECFFBC0A")
         (let [btree (unload-btree (reduce add
                                           (create (full-after-maximum-number-of-values 3))
                                           (range 20)))]
           
           (storage-keys-from-stored-nodes (:storage btree)
                                    (:root-id btree))))))


(defspec first-element-is-min-after-sorting ;; the name of the test
         100 ;; the number of iterations for test.check to test
         (properties/for-all [v (generators/not-empty (generators/vector generators/int))]
           (= (apply min v)
              (first (sort v)))))

(deftest test-garbage-collection
  #_(let [btree-atom (atom (create (full-after-maximum-number-of-values 3)))]

    (doseq [numbers (partition 3 (range 40))]
      (doseq [number numbers]
        (add-to-atom btree-atom number))
      (swap! btree-atom unload-excess-nodes 5))

    (unused-storage-keys @btree-atom)
    (swap! btree-atom unload-excess-nodes 5)
    @btree-atom
    
    )

  #_(add (unload-btree (add (create (full-after-maximum-number-of-values 3))
                          1))
       2)
  
  )

(comment
  (let [usage (-> (priority-map/priority-map [1 2] 1 [1 3] 2 [1 4] 3)
                  (assoc [1 2] 4))]
    
    (drop 1 usage)))

#_(defn start []
    (let [{:keys [btree storage]} (unload-btree (reduce add
                                                        (create (full-after-maximum-number-of-values 3))
                                                        (range 30))
                                                {}
                                                assoc)
          root-node-key (:storage-key (node btree
                                            (:root-id btree)))]
      #_(load-btree storage
                    root-node-key)
      btree
      #_(String. (get storage
                      (:storage-key (node btree
                                          (:root-id btree))))
                 "UTF-8")))



