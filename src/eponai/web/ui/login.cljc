(ns eponai.web.ui.login
  (:require
    [eponai.common.ui.index :as index]
    [eponai.common.ui.router :as router]
    [eponai.client.auth :as auth]
    [om.next :as om :refer [defui]]
    #?(:cljs [eponai.web.modules :as modules])))


(defui Login
  static om/IQuery
  (query [this]
    [{:proxy/index (om/get-query index/Index)}])
  Object
  (componentDidMount [this]
    (auth/show-lock (:shared/auth-lock (om/shared this))))
  (render [this]
    (index/->Index (:proxy/index (om/props this)))))

(def ->Login (om/factory Login))

(defmethod router/route->component :login [_] {:component Login})
#?(:cljs
   (modules/set-loaded! :login))