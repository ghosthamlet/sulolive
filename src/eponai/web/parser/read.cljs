(ns eponai.web.parser.read
  (:require [datascript.core :as d]
            [om.next :as om]
            [eponai.client.parser.read :as read]
            [eponai.common.parser :refer [read]]
            [eponai.common.parser.util :as p.util]
            [eponai.common.database.pull :as p]
            [taoensso.timbre :refer-macros [debug]]))

;; ################ Local reads ####################
;; Local reads goes here. These are specific to the
;; app running on this platform.
;; Remote reads should be defined in:
;;     eponai.client.parser.read

;; -------- Readers for UI components

(defmethod read :routing/app-root
  [{:keys [db query target parser] :as env} k p]
  (debug "routing/app-root with query: " query)
  (let [handler (:ui.component.root/route-handler (d/entity db [:ui/component :ui.component/root]))
        route-key (or (:route-key handler) :route/project)
        route-query (get query route-key)]
    (debug "Handler: " handler)
    (debug "route-key: " route-key)
    (debug "route-query:" route-query)
    (if target
      {:remote (om/query->ast [{:proxy/app-root route-query}])
       :value (parser env route-query)})))

;; TODO: A lot of target boilerplate here. Macro instead?
(defmethod read :query/active-project
  [{:keys [db _ target]} _ _]
  (when-not target
    {:value
     (let [project-eid (read/active-project-eid db)]
       (cond-> (into {} (d/entity db [:ui/component :ui.component/project]))
               (some? project-eid)
               (assoc :ui.component.project/active-project (d/entity db project-eid))))}))

(defmethod read :query/selected-transaction
  [{:keys [db query target]} _ _]
  (when-not target
    (read/read-entity-by-key db query [:ui/component :ui.component/transactions])))

(defmethod read :query/active-widget-open
  [{:keys [db query target]} _ _]
  (when-not target
    {:value (p/pull db query [:ui/component :ui.component/widget])}))

(defmethod read :query/active-widget
  [{:keys [db query target]} _ _]
  (when-not target
    (let [{:keys [ui.component.widget/id]} (p/lookup-entity db [:ui/component :ui.component/widget])]
     {:value (when (number? id)
               (p/pull db query id))})))

(defmethod read :query/widget-type
  [{:keys [db query target]} _ _]
  (when-not target
    {:value (p/pull db query [:ui/component :ui.component/widget])}))

(defmethod read :query/root-component
  [{:keys [db query target]} _ _]
  (when-not target
    (read/read-entity-by-key db nil [:ui/component :ui.component/root])))

(defmethod read :query/sidebar
  [{:keys [db query target]} _ _]
  (when-not target
    (read/read-entity-by-key db query [:ui/component :ui.component/sidebar])))