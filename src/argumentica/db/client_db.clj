(ns argumentica.db.client-db
  (:require (argumentica [btree-db :as btree-db]
                         [index :as index]
                         [sorted-set-db :as sorted-set-db]
                         [transaction-log :as transaction-log])
            (argumentica.db [client :as client]
                            [common :as common]
                            [server-api :as server-api]
                            [server-btree-db :as server-btree-db]
                            [server-transaction-log :as server-transaction-log]))
  (:use clojure.test))

(defn create [client]
  {:server-btree-db (server-btree-db/create client)
   :local-db (sorted-set-db/create)
   :client client})

(defn transact [client-db statements]
  (update client-db :local-db common/transact statements))

(defn refresh [client-db]
  (update client-db
          :server-btree-db
          server-btree-db/update))


(defn datoms [client-db entity-id attribute]
  (concat (server-btree-db/datoms (:server-btree-db client-db)
                                  entity-id
                                  attribute)
          (common/eat-datoms-from-eatcv (get-in (:local-db client-db) [:indexes :eatcv :index])
                                        entity-id
                                        attribute
                                        nil)))

(defn value [client-db entity-id attribute]
  (first (common/values-from-eatcv-statements (datoms client-db entity-id attribute))))

(defn transaction [client-db]
  (common/squash-transactions (map second (transaction-log/subseq (-> client-db :local-db :transaction-log)
                                                                  0))))

(defn commit [client-db]
  (client/transact (:client client-db)
                   (transaction client-db))
  (-> client-db
      (refresh)
      (assoc :local-db (sorted-set-db/create))))

(deftest test
  (let [server-state-atom (atom (server-api/create-state (btree-db/create-memory-btree-db)))
        client (client/->InProcessClient server-state-atom)
        client-db (create client)
        entity-id #uuid "adcba48b-b9a9-4c28-b1e3-3a97cb10cffb"]
    (server-api/transact server-state-atom
                         [[entity-id :name :set "Foo"]])
    (server-api/transact server-state-atom
                         [[entity-id :name :set "Bar"]])

    (is (= nil
           (value client-db entity-id :name)))

    (let [client-db (refresh client-db)]
      (is (= "Bar"
             (value client-db entity-id :name)))
      (let [client-db (-> client-db
                          (transact [[entity-id :name :set "Baz"]])
                          (transact [[entity-id :name :set "Baz2"]]))]
        (is (= "Baz2"
               (value client-db entity-id :name)))

        (is (= [[entity-id :name :set "Baz2"]]
               (transaction client-db)))

        (let [client-db (commit client-db)]
          (is (= []
                 (transaction client-db)))

          (is (= "Baz2"
                 (value client-db entity-id :name)))

          (is (= [[0 [[#uuid "adcba48b-b9a9-4c28-b1e3-3a97cb10cffb" :name :set "Foo"]]]
                  [1 [[#uuid "adcba48b-b9a9-4c28-b1e3-3a97cb10cffb" :name :set "Bar"]]]
                  [2 [[#uuid "adcba48b-b9a9-4c28-b1e3-3a97cb10cffb" :name :set "Baz2"]]]]
                 (server-api/transaction-log-subseq server-state-atom
                                                    0))))))))
