(ns eponai.server.api.store-test
  (:require
    [clojure.test :refer :all]
    [clojure.core.async :as async]
    [eponai.common.database :as db]
    [eponai.server.api.store :as store]
    [eponai.server.external.stripe :as stripe]
    [eponai.server.test-util :refer [new-db]]
    [eponai.server.external.aws-s3 :as s3]
    [taoensso.timbre :refer [debug]]
    [eponai.server.datomic.format :as f]))

(defn stripe-test [& [chan]]
  (reify stripe/IStripeAccount
    (create-product [this account-secret product]
      (when (and account-secret chan)
        (async/put! chan product)))
    (update-product [_ account-secret _ params]
      (when (and account-secret chan)
        (async/put! chan params)))
    (delete-product [_ account-secret product-id]
      (when (and account-secret chan)
        (async/put! chan product-id)))
    stripe/IStripeConnect))

(defn s3-test [chan]
  (reify s3/IAWSS3Photo
    (convert-to-real-key [this old-key])
    (move-photo [this bucket old-key new-key])
    (upload-photo [this params]
      (let [p (f/photo (:location params))]
        (async/put! chan p)
        p))))

(defn store-test []
  {:db/id        (db/tempid :db.part/user)
   :store/stripe {:db/id         (db/tempid :db.part/user)
                  :stripe/secret "stripe-secret"}
   :store/uuid   (db/squuid)})


;; ######################## CREATE #########################
(deftest create-product-no-image-test
  (testing "Create product with no photo should not add photo nil."
    (let [
          ;; Prepare existing data, store to be updated
          new-store (store-test)
          conn (new-db [new-store])

          ;; Fetch existing data from test DB
          db-store (db/pull (db/db conn) [:db/id] [:store/uuid (:store/uuid new-store)])

          ;; Prepare data for creating new products
          params {:name "product" :id (db/squuid)}
          stripe-chan (async/chan 1)
          s3-chan (async/chan 1)
          result (store/create-product {:state  conn
                                        :system {:system/aws-s3 (s3-test s3-chan)
                                                 :system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-store)
                                       params)

          ;; Pull new data after creation
          new-db-store (db/pull (:db-after result) [:db/id {:store/items [:store.item/uuid
                                                                          :store.item/photos]}] (:db/id db-store))
          db-product (first (get new-db-store :store/items))]

      ;; Verify
      (is (= 1 (count (:store/items new-db-store)))) ;;Verify that our store has one product
      (is (= (:store.item/uuid db-product) (:id params)))   ;;Verify that the store's item is the same as the newly created product
      (is db-product)                                       ;;Verify that the product was created in the DB
      (is (= (async/poll! stripe-chan) params))            ;; Verify that Stripe was called with the params
      (is (nil? (async/poll! s3-chan)))                     ;;Verify that S3 was called with the photo we wanted to upload
      (is (empty? (get db-product :store.item/photos))))))  ;; Verify that no photo entities were created for the product

(deftest create-product-with-image-test
  (testing "Create product with photo should add photo entity to product."
    (let [
          ;; Prepare existing data, store to be updated
          store (store-test)
          conn (new-db [store])

          ;; Fetch existing data from test DB
          db-store (db/pull (db/db conn) [:db/id] [:store/uuid (:store/uuid store)])

          ;; Prepare data for creating new products
          params {:name "product" :id (db/squuid) :photo {:location "someurl.com"}}
          stripe-chan (async/chan 1)
          s3-chan (async/chan 1)
          result (store/create-product {:state  conn
                                        :system {:system/aws-s3 (s3-test s3-chan)
                                                 :system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-store)
                                       params)
          ;; Pull new data after creation
          new-db-store (db/pull (:db-after result) [:db/id {:store/items [:store.item/uuid
                                                                          {:store.item/photos [:db/id :photo/path]}]}] (:db/id db-store))
          db-product (first (get new-db-store :store/items))
          db-photo (first (get db-product :store.item/photos))]

      ;; Verify
      (is (= 1 (count (:store/items new-db-store))))        ;;Verify that our store has one product
      (is (= (:store.item/uuid db-product) (:id params)))   ;;Verify that the store's item is the same as the newly created product
      (is (= (async/poll! stripe-chan) params))            ;;Verify that we called Stripe with params
      (is db-photo)                                         ;; Verify photo entity exists
      (is (= (:photo/path (async/poll! s3-chan)) (:photo/path db-photo))) ;;Verify that S3 was called with the photo we wanted to upload
      (is (= 1 (count (get db-product :store.item/photos)))) ;; Verify that we now have a photo entity for the product in the DB
      )))

;; ######################### UPDATE ############################

(deftest update-product-with-image-add-new-image
  (testing "Update a product that has an image with a new image, photo entity and other attributes should be updated."
    (let [
          ;; Prepare existing data to be updated
          old-photo (f/photo "old-photo")
          old-product (assoc (f/product {:name "product" :id (db/squuid)}) :store.item/photos [old-photo])
          store (assoc (store-test) :store/items [old-product])

          ;; Fetch existing data from our test DB
          conn (new-db [store])
          db-store (db/pull (db/db conn) [:db/id] [:store/uuid (:store/uuid store)])
          db-product (db/pull (db/db conn) [:db/id {:store.item/photos [:db/id :photo/path]}] [:store.item/uuid (:store.item/uuid old-product)])
          db-photo (first (get db-product :store.item/photos))

          ;; Prepare our update parameters
          new-params {:name "product-updated" :photo {:location "someurl.com"}}
          stripe-chan (async/chan 1)
          s3-chan (async/chan 1)
          result (store/update-product {:state  conn
                                        :system {:system/aws-s3 (s3-test s3-chan)
                                                 :system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-store)
                                       (:db/id db-product)
                                       new-params)

          ;; Pull new data after update
          new-db-product (db/pull (:db-after result) [:db/id :store.item/name {:store.item/photos [:db/id :photo/path]}] (:db/id db-product))
          new-db-photo (first (get new-db-product :store.item/photos))]

      ;; Verify
      (is (= (async/poll! stripe-chan) new-params))         ;; Verify that we called Stripe with the new params
      (is new-db-photo)                                     ;;Verify photo entity exists
      (is (= (:photo/path (async/poll! s3-chan)) (:photo/path new-db-photo))) ;; Verify that S3 was called with updated photo
      (is (= (:store.item/name new-db-product) (:name new-params))) ;; Verify that the name of the updated entity matches our parameters
      (is (= (:db/id db-photo) (:db/id new-db-photo)))      ;; Verify that we updated an existing photo and not created a new one
      (is (= 1
             (count (get new-db-product :store.item/photos))
             (count (get db-product :store.item/photos)))) ;; Verify that no new photo entities were created for product
      )))

(deftest update-product-with-image-no-new-image
  (testing "Update a product that has an image  without a new, photo entity should stay the same and other attributes should be updated."
    (let [
          ;; Prepare existing data to be updated
          old-photo (f/photo "old-photo")
          old-product (assoc (f/product {:name "product" :id (db/squuid)}) :store.item/photos [old-photo])
          store (assoc (store-test) :store/items [old-product])

          ;; Fetch existing data from our test DB
          conn (new-db [store])
          db-store (db/pull (db/db conn) [:db/id] [:store/uuid (:store/uuid store)])
          db-product (db/pull (db/db conn) [:db/id {:store.item/photos [:db/id :photo/path]}] [:store.item/uuid (:store.item/uuid old-product)])
          db-photo (first (get db-product :store.item/photos))

          ;; Prepare our update parameters
          new-params {:name "product-updated"}
          stripe-chan (async/chan 1)
          s3-chan (async/chan 1)
          result (store/update-product {:state  conn
                                        :system {:system/aws-s3 (s3-test s3-chan)
                                                 :system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-store)
                                       (:db/id db-product)
                                       new-params)

          ;; Pull new data after update
          new-db-product (db/pull (:db-after result) [:db/id :store.item/name {:store.item/photos [:db/id :photo/path]}] (:db/id db-product))
          new-db-photo (first (get new-db-product :store.item/photos))]

      ;; Verify
      (is (= (async/poll! stripe-chan) new-params))         ;; Verify that we called Stripe with the new params
      (is (nil? (async/poll! s3-chan))) ;; Verify that S3 was not called since we didn't have a photo
      (is (= (:store.item/name new-db-product) (:name new-params))) ;; Verify that the name of the updated entity matches our parameters
      (is new-db-photo)                                     ;;Verify photo entity exists
      (is (= (:db/id db-photo) (:db/id new-db-photo)))      ;; Verify that we updated an existing photo and not created a new one
      (is (= (:photo/path db-photo) (:photo/path new-db-photo)))  ;; Verify that the path is still the same in photo entity
      (is (= 1
             (count (get new-db-product :store.item/photos))
             (count (get db-product :store.item/photos)))) ;; Verify that no new photo entities were created for product
      )))

(deftest update-product-without-image-add-new-image
  (testing "Update a product that doesn't have a photo with a new photo, photo entity should be created and other attributes should be updated."
    (let [
          ;; Prepare existing data to be updated
          old-product (f/product {:name "product" :id (db/squuid)})
          store (assoc (store-test) :store/items [old-product])

          ;; Fetch existing data from our test DB
          conn (new-db [store])
          db-store (db/pull (db/db conn) [:db/id] [:store/uuid (:store/uuid store)])
          db-product (db/pull (db/db conn) [:db/id {:store.item/photos [:db/id :photo/path]}] [:store.item/uuid (:store.item/uuid old-product)])

          ;; Prepare our update parameters
          new-params {:name "product-updated" :photo {:location "someurl.com"}}
          stripe-chan (async/chan 1)
          s3-chan (async/chan 1)
          result (store/update-product {:state  conn
                                        :system {:system/aws-s3 (s3-test s3-chan)
                                                 :system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-store)
                                       (:db/id db-product)
                                       new-params)

          ;; Pull new data after update
          new-db-product (db/pull (:db-after result) [:db/id :store.item/name {:store.item/photos [:db/id :photo/path]}] (:db/id db-product))
          new-db-photo (first (get new-db-product :store.item/photos))]

      ;; Verify
      (is (empty? (get db-product :store.item/photos)))     ;;Verify first that our product had no photos
      (is (= (async/poll! stripe-chan) new-params))         ;; Verify that we called Stripe with the new params
      (is new-db-photo)                                     ;;verify new photo entoty exists
      (is (= (:photo/path (async/poll! s3-chan)) (:photo/path new-db-photo))) ;; Verify that S3 was called with created photo
      (is (= (:store.item/name new-db-product) (:name new-params))) ;; Verify that the name of the updated entity matches our parameters
      (is (= (get-in new-params [:photo :location]) (:photo/path new-db-photo)))  ;; Verify that the path is set to the photo we uploaded
      (is (= 1 (count (get new-db-product :store.item/photos)))) ;; Verify that a new photo entity was created for product
      )))

(deftest update-product-without-image-no-new-image
  (testing "Update a product that doesn't have an image, no photo should be added and other attributes should be updated."
    (let [
          ;; Prepare existing data to be updated
          old-product (f/product {:name "product" :id (db/squuid)})
          store (assoc (store-test) :store/items [old-product])

          ;; Fetch existing data from our test DB
          conn (new-db [store])
          db-store (db/pull (db/db conn) [:db/id] [:store/uuid (:store/uuid store)])
          db-product (db/pull (db/db conn) [:db/id {:store.item/photos [:db/id :photo/path]}] [:store.item/uuid (:store.item/uuid old-product)])

          ;; Prepare our update parameters
          new-params {:name "product-updated"}
          stripe-chan (async/chan 1)
          s3-chan (async/chan 1)
          result (store/update-product {:state  conn
                                        :system {:system/aws-s3 (s3-test s3-chan)
                                                 :system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-store)
                                       (:db/id db-product)
                                       new-params)

          ;; Pull new data after update
          new-db-product (db/pull (:db-after result) [:db/id :store.item/name {:store.item/photos [:db/id :photo/path]}] (:db/id db-product))]

      ;; Verify
      (is (empty? (get db-product :store.item/photos)))     ;;Verify first that our product had no photos
      (is (= (async/poll! stripe-chan) new-params))         ;; Verify that we called Stripe with the new params
      (is (nil? (async/poll! s3-chan))) ;; Verify that S3 was not called
      (is (= (:store.item/name new-db-product) (:name new-params))) ;; Verify that the name of the updated entity matches our parameters
      (is (empty? (get new-db-product :store.item/photos))) ;; Verify that no new photo entity was created for product
      )))


;; ################################################# DELETE ####################################################
(deftest delete-product
  (testing "Delete product, entity should be retracted."
    (let [
          ;; Prepare existing data to be deleted
          old-product (f/product {:name "product" :id (db/squuid)})
          store (assoc (store-test) :store/items [old-product])

          ;; Pull existing data from our test DB
          conn (new-db [store])
          db-store (db/pull (db/db conn) [:db/id :store/items] [:store/uuid (:store/uuid store)])
          db-product (db/pull (db/db conn) [:db/id] [:store.item/uuid (:store.item/uuid old-product)])

          ;; Prepare our delete
          stripe-chan (async/chan 1)
          result (store/delete-product {:state  conn
                                        :system {:system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-product))

          ;; Pull new data after delete
          new-db-store (db/pull (:db-after result) [:store/items] (:db/id db-store))
          new-db-product (db/pull (:db-after result) [:db/id] [:store.item/uuid (:store.item/uuid old-product)])]
      (debug "Old" old-product)

      ;; Verify
      (is (= 1 (count (:store/items db-store))))
      (is (:store.item/uuid old-product))
      (is (nil? new-db-product))
      (is (empty? (:store/items new-db-store)))             ;; Verify that our store has no products anymore
      (is (= (async/poll! stripe-chan) (str (:store.item/uuid old-product))))  ;; Verify that we called Stripe with the UUID of the product to remove
      )))

(deftest delete-product-with-image
  (testing "Delete product, entity should be retracted."
    (let [
          ;; Prepare existing data to be deleted
          old-photo (f/photo "old-photo")
          old-product (assoc (f/product {:name "product" :id (db/squuid)}) :store.item/photos [old-photo])
          store (assoc (store-test) :store/items [old-product])

          ;; Pull existing data from our test DB
          conn (new-db [store])
          db-store (db/pull (db/db conn) [:db/id :store/items] [:store/uuid (:store/uuid store)])
          db-product (db/pull (db/db conn) [:db/id] [:store.item/uuid (:store.item/uuid old-product)])
          db-photo (db/one-with (db/db conn) {:where   '[[?e :photo/path ?p]]
                                              :symbols {'?p "old-photo"}})

          ;; Prepare our delete
          stripe-chan (async/chan 1)
          result (store/delete-product {:state  conn
                                        :system {:system/stripe (stripe-test stripe-chan)}}
                                       (:db/id db-product))

          ;; Pull new data after delete
          new-db-store (db/pull (:db-after result) [:store/items] (:db/id db-store))
          new-db-product (db/pull (:db-after result) [:db/id] [:store.item/uuid (:store.item/uuid old-product)])
          new-db-photo (db/one-with (:db-after result) {:where '[[?e :photo/path ?p]]
                                                        :symbols {'?p "old-photo"}})]

      ;; Verify
      (is db-photo)
      (is (= 1 (count (:store/items db-store))))            ;;Verify our store started with one product
      (is (nil? new-db-product))                            ;;Verify that product was retracted from DB
      (is (nil? new-db-photo))                              ;;Verify that photo entity was retracted from DB
      (is (empty? (:store/items new-db-store)))             ;; Verify that our store has no products anymore
      (is (= (async/poll! stripe-chan) (str (:store.item/uuid old-product))))  ;; Verify that we called Stripe with the UUID of the product to remove
      )))