(ns eponai.web.app
  (:require [om.next :as om :refer-macros [defui]]
            [sablono.core :refer-macros [html]]
            [goog.dom :as gdom]
    ;; To initialize ReactDOM:
            [cljsjs.react.dom]
            [datascript.core :as d]
            [eponai.client.backend :as backend]
            [eponai.client.parser.merge :as merge]
            [eponai.web.parser.merge :as web.merge]
            [eponai.web.history :as history]
            [eponai.web.parser.mutate]
            [eponai.web.parser.read]
            [eponai.common.validate]
            [eponai.web.homeless :as homeless]
            [eponai.common.datascript :as common.datascript]
            [eponai.common.parser :as parser]
            [eponai.common.report]
            [eponai.web.ui.navigation :as nav]
            [taoensso.timbre :refer-macros [info debug error trace]]
            [eponai.client.ui :refer-macros [opts]]
            [eponai.web.ui.utils :as utils]))

(defui ^:once App
  static om/IQueryParams
  (params [_]
    (history/url-query-params (history/url-handler-form-token)))
  static om/IQuery
  (query [this]
    (let [{:keys [url/component]} (if (om/component? this) (om/get-params this) (om/params this))
          subquery (om/get-query component)]
      [:datascript/schema
      :user/current
      {:proxy/nav-bar (om/get-query nav/NavbarMenu)}
      {:proxy/side-bar (om/get-query nav/SideBar)}
      {:proxy/app-content subquery}]))
  Object
  (initLocalState [_]
    {:sidebar-visible? false})
  (render
    [this]
    (let [{:keys [proxy/app-content
                  proxy/nav-bar
                  proxy/side-bar]} (om/props this)
          {:keys [sidebar-visible?]} (om/get-state this)
          {:keys [url/factory]} (om/get-params this)]
      (html
        [:div
         [:div#wrapper
          (when sidebar-visible?
            {:class "sidebar-visible"})
          (nav/->SideBar (om/computed side-bar
                                      {:on-close (when sidebar-visible?
                                                   #(om/update-state! this assoc :sidebar-visible? false))}))
          (nav/->NavbarMenu (om/computed nav-bar
                                         {:on-sidebar-toggle #(om/update-state! this assoc :sidebar-visible? true)}))

          ;[:div
          ; (opts {:style {:position        :fixed
          ;                :height          "100vh"
          ;                :width           "100%"
          ;                :background      "transparent url(/style/img/world-black.png) no-repeat center center"
          ;                :background-size :cover
          ;                :opacity         0.05}})]
          ]
         [:div#page-content
          (opts {:class "container-fluid content-section"
                 :style {:border "1px solid transparent"}})
          (when factory
            (factory (assoc app-content :ref :content)))]]))))

(defonce conn-atom (atom nil))

(defn init-conn
  "Sets up the datascript state. Caches the state so we can keep our app state between
  figwheel reloads."
  []
  (if @conn-atom
    (do
      (debug "Reusing old conn. It currently has schema for attributes:" (-> @conn-atom deref :schema keys))
      @conn-atom)
    (let [ui-schema (common.datascript/ui-schema)
          ui-state [{:ui/singleton :ui.singleton/app}
                    {:ui/singleton :ui.singleton/auth}
                    {:ui/component :ui.component/project
                     :ui.component.project/selected-tab :dashboard}
                    {:ui/component :ui.component/widget}]
          conn (d/create-conn ui-schema)]
      (d/transact! conn ui-state)
      (reset! conn-atom conn))))

(defn remote-fn [conn]
  (let [f (backend/post-to-url homeless/om-next-endpoint-user-auth)]
    (fn [query]
     (let [ret (f query)
           db (d/db conn)]
       (assoc-in ret [:opts :transit-params :eponai.common.parser/read-basis-t]
                 (some->> (d/q '{:find [?e .] :where [[?e :db/ident :eponai.common.parser/read-basis-t]]}
                               db)
                          (d/entity db)
                          (d/touch)
                          (into {})))))))

(defn initialize-app [conn]
  (debug "Initializing App")
  (let [parser (parser/parser)
        reconciler (om/reconciler {:state   conn
                                   :parser  parser
                                   :remotes [:remote]
                                   :send    (backend/send! {:remote (remote-fn conn)})
                                   :merge   (merge/merge! web.merge/web-merge)
                                   :migrate nil})
        history (history/init-history reconciler)]
    (reset! utils/reconciler-atom reconciler)
    (om/add-root! reconciler App (gdom/getElement "my-app"))
    (history/start! history)))

(defn run []
  (info "Run called in: " (namespace ::foo))
  (initialize-app (init-conn)))
