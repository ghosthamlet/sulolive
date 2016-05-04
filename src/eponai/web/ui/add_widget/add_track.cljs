(ns eponai.web.ui.add-widget.add-track
  (:require
    [eponai.client.ui :refer [update-query-params!] :refer-macros [opts]]
    [eponai.common.report :as report]
    [eponai.web.ui.add-widget.chart-settings :refer [->ChartSettings]]
    [eponai.web.ui.add-widget.select-graph :refer [->SelectGraph]]
    [eponai.web.ui.utils.filter :as filter]
    [eponai.web.ui.widget :refer [->Widget]]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug]]))

(defn- change-graph-style [widget style]
  (let [default-group-by {:graph.style/bar    :transaction/tags
                          :graph.style/area   :transaction/date
                          :graph.style/line   :transaction/date
                          :graph.style/number :default}]
    (-> widget
        (assoc-in [:widget/graph :graph/style] style)
        (update-in [:widget/report :report/track :track/functions]
                   (fn [fns]
                     (map (fn [f]
                            (assoc f
                              :track.function/id :track.function.id/sum
                              :track.function/group-by (get default-group-by style)))
                          fns))))
    ;(om/update-state! component
    ;                  #(-> %
    ;                       (assoc-in [:input-graph :graph/style] style)
    ;                       (assoc-in [:input-report :report/track :track/functions] [{:track.function/id :track.function.id/sum
    ;                                                                                  :track.function/group-by (get default-group-by style)}])))
    ))

(defui NewTrack
  Object
  (initLocalState [this]
    {:step 1
     :selected-transactions :all-transactions})
  (set-filters [this props]
    (let [{:keys [widget]} (::om/computed props)]
      (om/update-state! this assoc
                        :tag-filter (select-keys (:widget/filter widget) [:filter/include-tags
                                                                          :filter/exclude-tags])
                        :graph-filter (select-keys (get-in widget [:widget/graph :graph/filter]) [:filter/include-tags
                                                                                                  :filter/exclude-tags])
                        :date-filter (select-keys (:widget/filter widget) [:filter/start-date
                                                                           :filter/end-date
                                                                           :filter/last-x-days]))))
  (get-filters [this]
    (let [{:keys [tag-filter date-filter]} (om/get-state this)]
      (merge tag-filter date-filter)))

  (update-selected-transactions [this selected]
    (let [{:keys [widget
                  on-change]} (om/get-computed this)]
      (when (= selected :all-transactions)
        (on-change (assoc widget :widget/filter {}) {:update-data? true}))
      (om/update-state! this (fn [st]
                               (cond-> (assoc st :selected-transactions selected)

                                       (= selected :all-transactions)
                                       (assoc :tag-filter {}))))))
  (componentDidMount [this]
    (.set-filters this (om/props this)))
  (componentWillReceiveProps [this new-props]
    (.set-filters this new-props))

  (render [this]
    (let [{:keys [widget
                  transactions
                  on-change]} (om/get-computed this)
           {:keys [step
                   tag-filter
                   date-filter
                   graph-filter
                   selected-transactions]} (om/get-state this)
          {:keys [widget/graph]} widget]
      (html
        [:div
         ;[:ul.breadcrumbs
         ; [:li
         ;  [:a.disabled
         ;   (if (:db/id widget)
         ;     "Edit Track"
         ;     "New Track")]]
         ; (if (= 1 step)
         ;   [:li
         ;    [:span "Style"]]
         ;   [:li
         ;    [:a
         ;     {:on-click #(om/update-state! this assoc :step 1)}
         ;     [:span "Style"]]])
         ; (if (= 2 step)
         ;   [:li
         ;    [:span "Settings"]]
         ;   [:li
         ;    [:a
         ;     {:on-click #(om/update-state! this assoc :step 2)}
         ;     [:span "Settings"]]])]
         [:div.row.columns.small-12.medium-8
          [:div
           [:p.currency-code "Filter"]
           [:div.row
            [:div.columns.small-12
             [:div
              (filter/->DateFilter (om/computed {:filter date-filter}
                                                {:on-change #(do
                                                              (om/update-state! this assoc :date-filter %)
                                                              (on-change (assoc widget :widget/filter %) {:update-data? true}))}))]]]
           [:div.row
            [:div.columns.small-12.medium-6
             [:select
              {:value     (name selected-transactions)
               :on-change #(.update-selected-transactions this (keyword (.-value (.-target %))))}
              [:option
               {:value (name :all-transactions)}
               "All transactions"]
              [:option
               {:value (name :include-tags)}
               "Transactions with tags"]
              [:option
               {:value (name :exclude-tags)}
               "Transactions without tags"]]]
            [:div.columns.small-12.medium-6
             (let [tag-filter-fn (fn [tag-filter-key]
                                   (filter/->TagFilter (om/computed {:tags        (get tag-filter tag-filter-key)}
                                                                    {:on-change (fn [tags]
                                                                                  (let [new-filters (if (seq tags)
                                                                                                      {tag-filter-key tags}
                                                                                                      (dissoc tag-filter tag-filter-key))]
                                                                                    (om/update-state! this assoc :tag-filter new-filters)
                                                                                    (on-change (assoc widget :widget/filter new-filters) {:update-data? true})))})))]
               (condp = selected-transactions
                 :include-tags (tag-filter-fn :filter/include-tags)
                 :exclude-tags (tag-filter-fn :filter/exclude-tags)
                 :all-transactions nil))]]
           [:hr]
           [:p.currency-code "Preview"]
           [:div.row
            [:div.columns.small-12
             (->Widget (assoc widget :widget/data (report/generate-data (:widget/report widget) transactions {:data-filter (get-in widget [:widget/graph :graph/filter])})))]]

           [:div.row
            (let [style (:graph/style graph)]
              [:div.columns.small-12
               [:input
                {:type     "radio"
                 :id       "bar-option"
                 :checked  (= style :graph.style/bar)
                 :on-click #(when (and on-change (not= style :graph.style/bar))
                             (on-change (change-graph-style widget :graph.style/bar)))}]
               [:label {:for "bar-option"}
                [:span.currency-code "Bars"]]
               [:input
                {:type     "radio"
                 :id       "number-option"
                 :checked  (= style :graph.style/number)
                 :on-click #(when (and on-change (not= style :graph.style/number))
                             (on-change (change-graph-style widget :graph.style/number)))}]
               [:label {:for "number-option"}
                [:span.currency-code "Number"]]
               [:input
                {:type     "radio"
                 :id       "area-option"
                 :checked  (= style :graph.style/area)
                 :on-click #(when (and on-change (not= style :graph.style/area))
                             (on-change (change-graph-style widget :graph.style/area)))}]
               [:label {:for "area-option"}
                [:span.currency-code "Area"]]
               [:input
                {:type     "radio"
                 :id       "line-option"
                 :checked  (= style :graph.style/line)
                 :on-click #(when (and on-change (not= style :graph.style/line))
                             (on-change (change-graph-style widget :graph.style/line)))}]
               [:label {:for "line-option"}
                [:span.currency-code "Line"]]])]

           (when (= :graph.style/bar (:graph/style graph))
             [:hr])
           (when (= :graph.style/bar (:graph/style graph))

             [:div.row
              [:div.columns.small-12.medium-6
               [:span.currency-code "Show tags"]]
              [:div.columns.small-12.medium-6
               [:span.currency-code "Hide tags"]]])

           (when (= :graph.style/bar (:graph/style graph))
             [:div.row
              [:div.columns.small-12.medium-6
               (filter/->TagFilter (om/computed {:tags        (:filter/include-tags graph-filter)}
                                                {:on-change (fn [tags]
                                                              (let [new-filters (if (seq tags)
                                                                                  (merge graph-filter {:filter/include-tags tags})
                                                                                  (dissoc graph-filter :filter/include-tags))]
                                                                (om/update-state! this assoc :graph-filter new-filters)
                                                                (on-change (assoc-in widget [:widget/graph :graph/filter] new-filters) {:update-data? false})))}))]
              [:div.columns.small-12.medium-6
               (filter/->TagFilter (om/computed {:tags        (:filter/exclude-tags graph-filter)}
                                                {:on-change (fn [tags]
                                                              (let [new-filters (if (seq tags)
                                                                                  (merge graph-filter {:filter/exclude-tags tags})
                                                                                  (dissoc graph-filter :filter/exclude-tags))]
                                                                (om/update-state! this assoc :graph-filter new-filters)
                                                                (on-change (assoc-in widget [:widget/graph :graph/filter] new-filters) {:update-data? false})))}))]])]]]))))

(def ->NewTrack (om/factory NewTrack))
