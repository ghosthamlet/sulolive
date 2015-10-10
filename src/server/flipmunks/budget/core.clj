(ns flipmunks.budget.core
  (:gen-class)
  (:require [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :as middleware]
            [ring.middleware.resource :as middleware.res]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [flipmunks.budget.datomic.core :as dt]))

;; Handle fetch currency data.
(defn currencies [app-id]
  (let [url (str "https://openexchangerates.org/api/currencies.json?app_id=" app-id)]
    (json/read-str (:body (client/get url)) :key-fn keyword)))


(defn currency-rates [app-id date-str]
  (let [url (str "https://openexchangerates.org/api/historical/" date-str ".json?app_id=" app-id)]
    (json/read-str (:body (client/get url)) :key-fn keyword)))

(defn respond-data
  "Create request response based on params."
  [params]
  (let [db-data (dt/pull-data params)
        db-schema (dt/pull-schema db-data)]
    {:schema   (vec (filter dt/schema-required? db-schema))
     :entities db-data}))

(defroutes app-routes
           (context "/entries" [] (defroutes entries-routes
                                             (GET "/" {params :params} (str (respond-data params)))
                                             (POST "/" {body :body} (str (dt/post-user-tx body)))))
           (route/not-found "Not Found"))

(def app
  (-> (handler/api app-routes)
      (middleware.res/wrap-resource "public")
      (middleware/wrap-json-body {:keywords? true})
      (middleware/wrap-json-response)))

(defn -main [& args]
  )
