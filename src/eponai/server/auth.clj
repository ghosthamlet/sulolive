(ns eponai.server.auth
  (:require
    [buddy.auth]
    [buddy.auth.accessrules :as buddy]
    [buddy.auth.backends :as backends]
    [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
    [buddy.auth.protocols :as auth.protocols]
    [manifold.deferred :as deferred]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [error debug]]
    [ring.util.response :as r]
    [eponai.server.datomic.format :as f]
    [eponai.server.external.auth0 :as auth0]
    [eponai.common.database :as db]
    [eponai.common.routes :as routes]
    [eponai.common :as c]))

(def auth-token-cookie-name "sulo-auth-token")

(def restrict buddy/restrict)

(defprotocol IAuthResponder
  (-redirect [this path])
  (-prompt-login [this request])
  (-unauthorize [this request]))

(defn prompt-login [request]
  (-prompt-login (::auth-responder request) request))

(defn unauthorize [request]
  (-unauthorize (::auth-responder request) request))

(defn redirect [request path]
  (-redirect (::auth-responder request) path))

(defn remove-auth-cookie [response]
  (assoc-in response [:cookies auth-token-cookie-name] {:value "kill" :max-age 1}))

(defrecord HttpAuthResponder []
  IAuthResponder
  (-redirect [this path]
    (r/redirect path))
  (-prompt-login [this request]
    (-redirect this (routes/path :login)))
  (-unauthorize [this request]
    (-redirect this (routes/path :unauthorized))))

(defn authenticate-auth0-user [conn auth0-user]
  (when auth0-user
    (let [db-user (db/lookup-entity (db/db conn) [:user/email (:email auth0-user)])]
      (when-not db-user
        (let [new-user (f/auth0->user auth0-user)]
          (db/transact-one conn new-user))))))

(defn jwt-cookie-backend [conn auth0]
  (let [jws-backend (backends/jws {:secret   (auth0/secret auth0)
                                   ;; Throw it so we can handle it in our wrapper
                                   :on-error (fn [r e]
                                               (throw e))})]
    ;; Only changes how the token is parsed. Parses from cookie instead of header
    (reify
      auth.protocols/IAuthentication
      (-parse [_ request]
        (get-in request [:cookies auth-token-cookie-name :value]))
      (-authenticate [_ request data]
        (try
          (let [auth0-user (auth.protocols/-authenticate jws-backend request data)]
           (when (some? auth0-user)
             (authenticate-auth0-user conn auth0-user)
             auth0-user))
          (catch Exception e
            (if-let [token-failure (when-let [data (ex-data e)]
                                     (let [{:keys [type cause]} data]
                                       (when (= type :validation)
                                         (condp = cause
                                           :exp ::token-expired
                                           :signature ::token-manipulated))))]
              token-failure
              (do
                ;; TODO: Return nil to support multiple auth backends?
                (error "Error authenticating jws token: " e)
                (throw e))))))
      auth.protocols/IAuthorization
      (-handle-unauthorized [_ request metadata]
        (auth.protocols/-handle-unauthorized jws-backend request metadata)))))

(defn- wrap-http-auth-responder [handler]
  (fn [request]
    (handler (assoc request ::auth-responder (->HttpAuthResponder)))))

(defn- wrap-expired-token [handler]
  (let [token-failure #{::token-manipulated ::token-expired}]
    (fn [request]
      (if-not (token-failure (:identity request))
        (handler request)
        (do
          (debug "Auth token failure: " (:identity request)
                 ", executing the request without auth. Will remove token from cookie.")
          (deferred/let-flow [response (handler (dissoc request :identity))]
                             (cond-> response
                                     ;; On token expired, if no-one has set the cookies field, reset it.
                                     (empty? (get-in response [:cookies auth-token-cookie-name]))
                                     (remove-auth-cookie))))))))

(defn wrap-auth [handler conn auth0]
  (let [auth-backend (jwt-cookie-backend conn auth0)]
    (-> handler
        (wrap-expired-token)
        (wrap-authentication auth-backend)
        (wrap-authorization auth-backend)
        (wrap-http-auth-responder))))

(defn authenticate [{:keys [params] :as request}]
  (let [auth0 (get-in request [:eponai.server.middleware/system :system/auth0])
        {:keys [code state]} params
        {:keys [redirect-url token]} (auth0/authenticate auth0 code state)]
    (if token
      (r/set-cookie (r/redirect redirect-url) auth-token-cookie-name token)
      (prompt-login request))))

;; TODO: Might want to structure this differently.
;;       Because how do we query "which role does this user have for which parameters?"
;;       We'd want, User has ::exact-user role for :user-id [123]
;;                  User has ::store-owner role for :store-id [234 345]?
;;      This might not be useful.
(defn auth-query [roles {:keys [email]} route-params]
  (let [roles (cond-> roles
                      (keyword? roles) (hash-set)
                      (sequential? roles) (set))
        user-id (when (::routes/user roles)
                  (c/parse-long-safe
                    (or (:user-id route-params)
                        (get-in route-params [:user :db/id]))))
        store-id (when (::routes/store-owner roles)
                   (c/parse-long-safe
                     (or (:store-id route-params)
                         (get-in route-params [:store :db/id]))))]
    (when (and (seq roles) (string? email))
      (cond-> {:find    '[?user .]
               :where   '[[?user :user/email ?email]]
               :symbols {'?email email}}
              (some? user-id)
              (db/merge-query {:symbols {'?user user-id}})
              (some? store-id)
              (db/merge-query {:where   '[[?store :store/owners ?owner]
                                          [?owner :store.owner/user ?user]]
                               :symbols {'?store store-id}})))))

(defn is-public-role? [roles]
  (= ::routes/public
     (cond-> roles (not (keyword? roles)) (first))))

(defn authed-for-roles? [db roles auth route-params]
  (boolean (or (is-public-role? roles)
               (when-let [query (auth-query roles auth route-params)]
                 (debug "query: " query " role: " roles)
                 (db/find-with db query)))))

(defn bidi-route-restrictions [route]
  (let [auth-roles (routes/auth-roles route)]
    {:handler  (fn [{:keys [identity route-params] :as request}]
                 (let [auth-val {:route        route
                                 :route-params route-params
                                 :auth-roles   auth-roles
                                 :auth         identity}]
                   (if (authed-for-roles? (db/db (:eponai.server.middleware/conn request))
                                          auth-roles
                                          identity
                                          route-params)
                     (buddy/success auth-val)
                     (buddy/error auth-val))))
     :on-error (fn [request v]
                 (debug "Unable to authorize user: " v)
                 (if (nil? (:auth v))
                   (prompt-login request)
                   (unauthorize request)))}))
