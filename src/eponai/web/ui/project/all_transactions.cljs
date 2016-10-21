(ns eponai.web.ui.project.all-transactions
  (:require
    [cljs-time.core :as time]
    [cljs.reader :as reader]
    [clojure.string :as string]
    [datascript.core :as d]
    [eponai.client.lib.transactions :as lib.t]
    [eponai.client.ui :refer [map-all update-query-params!] :refer-macros [style opts]]
    [eponai.common.format.date :as date]
    [eponai.web.ui.project.add-transaction :refer [->AddTransaction AddTransaction]]
    [eponai.web.ui.daterangepicker :refer [->DateRangePicker]]
    [eponai.web.ui.utils :as utils]
    [eponai.web.ui.utils.filter :as filter]
    [eponai.web.ui.utils.infinite-scroll :as infinite-scroll]
    [garden.core :refer [css]]
    [eponai.web.ui.select :as sel]
    [goog.string :as gstring]
    [goog.events :as events]
    [om.dom :as dom]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug warn]]
    [clojure.data :as diff]))

(defn- trim-decimals [n] (int n))

(defn select-first-digit
  "Selects the first digit (most valuable digit) in the input dom node."
  [dom-node]
  ;; make sure we're in text mode
  (when (not= "text" (.-type dom-node))
    (warn "Dom node was not in type text. Was: " (.-type dom-node) " for dom node: " dom-node))
  (.setSelectionRange dom-node 0 0))

(defn two-decimal-string [s]
  (gstring/format "%.2f" (str s)))

(defn empty-sorted-tag-set []
  (sorted-set-by #(compare (:tag/name %) (:tag/name %2))))

;; ################### Om next components ###################

(defui Transaction
  static om/IQuery
  (query [_] lib.t/full-transaction-pull-pattern)

  utils/ISyncStateWithProps
  (props->init-state [_ props]
    (let [transaction (-> props
                          (->> (into {} (filter #(or (= "transaction" (namespace (key %)))
                                                     (= :db/id (key %))))))
                          (update :transaction/amount two-decimal-string)
                          (update :transaction/tags
                                  (fn [tags]
                                    (into (empty-sorted-tag-set)
                                          (map #(select-keys % [:tag/name]))
                                          tags))))]
      {:input-transaction transaction
       :init-state transaction}))

  Object
  (initLocalState [this]
    (merge
      ;; Never change this computed function.
      {:computed/date-range-picker-on-apply #(do (om/update-state! this assoc-in
                                                                   [:input-transaction
                                                                    :transaction/date] %)
                                                 (.deselect this))
       :on-deselect-fn #(.on-mouse-event this %)}
      (utils/props->init-state this (om/props this))))

  (componentWillReceiveProps [this props]
    (utils/sync-with-received-props this props))

  (save-edit [this]
    (let [{:keys [input-transaction
                  init-state]} (om/get-state this)
          diff (diff/diff init-state input-transaction)
          ;added-tags (or (:transaction/tags (second diff)) [])
          removed-tags (or (filter some? (:transaction/tags (first diff))) [])
          ]
      ;; Transact only when we have a diff to avoid unecessary mutations.
      (when-not (= diff [nil nil init-state])
        ;(debug "Delete tag Will transacti diff: " diff)
        (om/transact! this `[(transaction/edit ~{:old init-state :new input-transaction})
                             :query/transactions]))))
  (select [this]
    (let [{:keys [is-selected? on-deselect-fn]} (om/get-state this)]
      (debug "Select: " on-deselect-fn)
      (when-not is-selected?
        (om/update-state! this assoc :is-selected? true)
        (.. js/document (addEventListener "click" on-deselect-fn)))))

  (on-mouse-event [this event]
    (let [{:keys [input-transaction is-selected? on-deselect-fn]} (om/get-state this)
          transaction-id (str (:db/id input-transaction))
          should-deselect? (not (some #(= (.-id %) transaction-id) (.-path event)))]
      (when should-deselect?
        (.. js/document (removeEventListener "click" on-deselect-fn))
        (when is-selected?
          (.deselect this)))))

  (deselect [this]
    (om/update-state! this assoc :is-selected? false)
    (.save-edit this))
  (render [this]
    (let [{:keys [input-transaction]} (om/get-state this)
          {:keys [db/id
                  transaction/date
                  transaction/currency
                  transaction/amount
                  transaction/type
                  transaction/conversion
                  transaction/category
                  transaction/title]
           :as   transaction} (or input-transaction (om/props this))
          {:keys [computed/date-range-picker-on-apply is-selected?]} (om/get-state this)
          {:keys [currencies all-tags]} (om/get-computed this)]
      (dom/li
        #js {:className    (str "row collapse align-middle is-collapse-child" (when is-selected? " is-selected"))
             :id (str id)
             :onClick #(.select this)}
        ;; Amount in main currency
        (dom/div
          #js {:className "amount"}
          (if-let [rate (:conversion/rate conversion)]
            (let [amount (cond-> amount (string? amount) (reader/read-string))]
              (if (= (:db/ident type) :transaction.type/expense)
                (dom/strong #js {:className "expense"}
                            (str "-" (two-decimal-string (/ amount rate))))
                (dom/strong #js {:className "income"}
                            (two-decimal-string (/ amount rate)))))
            (dom/i #js {:className "fa fa-spinner fa-spin"})))

        ;; Date
        (dom/div
          #js {:className "date"}
          (->DateRangePicker (om/computed {:single-calendar? true
                                           :start-date       (date/date-time date)}
                                          {:on-apply date-range-picker-on-apply
                                           :format   "MMM dd"})))
        (dom/div
          #js {:className "category"}
          (sel/->Select {:value {:label (:category/name category) :value (:db/id category)}
                         :options [{:label (:category/name category) :value (:db/id category)}]}))

        ;; Title
        (dom/div
          #js {:className "note"}
          (dom/input
            #js {:className "title"
                 :value     (or title "")
                 :type      "text"
                 :onChange  #(om/update-state! this assoc-in [:input-transaction :transaction/title] (.-value (.-target %)))
                 :onKeyDown #(utils/on-enter-down % (fn [_]
                                                      (.blur (.-target %))))
                 :onBlur    #(.deselect this)}))

        ;; Tags
        (dom/div
          #js {:className "tags"}
          (sel/->SelectTags (om/computed {:value    (map (fn [t]
                                                           {:label (:tag/name t)
                                                            :value (:db/id t)})
                                                         (:transaction/tags transaction))
                                          :options  (map (fn [t]
                                                           {:label (:tag/name t)
                                                            :value (:db/id t)})
                                                         all-tags)}
                                         {:on-select (fn [selected]
                                                       (om/update-state! this assoc-in
                                                                         [:input-transaction :transaction/tags]
                                                                         (into (empty-sorted-tag-set)
                                                                               (map (fn [t]
                                                                                      {:tag/name (:label t)}))
                                                                               selected)))
                                          :onClur    #(.deselect this)})))

        ;; Amount in local currency
        (dom/div
          #js {:className "local-amount"}
          (dom/input
            #js {:tabIndex  -1
                 :className "input-amount text-right"
                 :value     (or amount "")
                 :pattern   "[0-9]+([\\.][0-9]+)?"
                 :type      "text"
                 :onChange  #(om/update-state! this assoc-in [:input-transaction :transaction/amount] (.-value (.-target %)))
                 :onKeyDown #(condp = (.-keyCode %)
                              ;; Prevent default on comma and period/dot.
                              events/KeyCodes.COMMA (.preventDefault %)
                              events/KeyCodes.PERIOD (when (string/includes? (str amount) ".") (.preventDefault %))
                              ;; Save edit on enter.
                              events/KeyCodes.ENTER (utils/on-enter-down % (fn [_] (.blur (.-target %))))
                              true)
                 :ref       (str "amount-" id)
                 :onBlur    #(do
                              (let [val (.-value (.-target %))]
                                ;; When the value entered doesn't have 2 decimals, set it to be 2 decimals.
                                ;; TODO: What if we need more decimals? What happens? This change was made
                                ;;       to fix UI issues when there are less than 2 decimals.
                                (when (not= val (two-decimal-string val))
                                  (om/update-state! this assoc-in [:input-transaction :transaction/amount] (two-decimal-string val))))
                              (.deselect this))}))
        (dom/div
          #js {:className "currency"}

          (sel/->Select (om/computed {:value    {:label (:currency/code currency) :value (:db/id currency)}
                                      :options  (map (fn [c]
                                                       {:label (:currency/code c)
                                                        :value (:db/id c)})
                                                     currencies)}
                                     {:on-select (fn [selected]
                                                   (om/update-state! this assoc-in [:input-transaction :transaction/currency] (:value selected))
                                                   (.deselect this))})))
        ))))

(def ->Transaction (om/factory Transaction {:keyfn :db/id}))

(defn date-range-from-filter [{:keys [filter/last-x-days filter/start-date filter/end-date]}]
  (if (some? last-x-days)
    (let [t (date/today)]
      {:start-date (time/minus t (time/days last-x-days))
       :end-date   t})
    {:start-date (date/date-time start-date)
     :end-date   (date/date-time end-date)}))

(defui AllTransactions
  static om/IQueryParams
  (params [_]
    {:filter {}
     :transaction (om/get-query Transaction)})
  static om/IQuery
  (query [_]
    ['({:query/transactions ?transaction} {:filter ?filter})
     {:query/current-user [:user/uuid
                           {:user/currency [:currency/code
                                            :currency/symbol-native]}]}
     {:query/all-currencies [:currency/code]}
     {:query/all-projects [:project/uuid
                           :project/name]}
     {:query/all-tags [:tag/name]}
     {:proxy/add-transaction (om/get-query AddTransaction)}])

  Object
  (initLocalState [this]
    {:computed/transaction-on-tag-click   (fn [tag]
                                            (om/update-query! this update-in [:params :filter :filter/include-tags]
                                                              #(utils/add-tag % tag)))
     :computed/tag-filter-on-change       (fn [tags]
                                            (om/update-query! this update-in [:params :filter]
                                                              #(assoc % :filter/include-tags tags)))
     :computed/amount-filter-on-change    (fn [{:keys [filter/min-amount filter/max-amount]}]
                                            (om/update-query! this update-in [:params :filter]
                                                              #(assoc % :filter/min-amount min-amount
                                                                        :filter/max-amount max-amount)))
     :computed/date-range-picker-on-apply (fn [{:keys [start-date end-date selected-range]}]
                                            (om/update-query! this update-in [:params :filter]
                                                              #(assoc % :filter/start-date (date/date-map start-date)
                                                                        :filter/end-date (date/date-map end-date))))
     :computed/infinite-scroll-node-fn    (fn []
                                            ;; TODO: Un-hack this.
                                            ;; HACK: I don't know how we can get the page-content div in any other way.
                                            (some-> (om/get-reconciler this)
                                                    (om/app-root)
                                                    (om/react-ref (str :eponai.web.ui.root/page-content-ref))
                                                    (js/ReactDOM.findDOMNode)))})

  (componentDidMount [this]
    (when (zero? (:list-size (om/get-state this)))
      (debug "Updating list-size!")
      (om/update-state! this assoc :list-size 50)))

  (has-filter [this]
    (some #(let [v (val %)] (if (coll? v) (seq v) (some? v)))
          (:filter (om/get-params this))))

  (render-empty-message [this]
    (html
      [:div
       [:a.button
        (opts {:style {:visibility :hidden}})
        "Button"]
       [:div.empty-message.text-center
        ;[:i.fa.fa-usd.fa-4x]
        ;[:i.fa.fa-eur.fa-4x]
        ;[:i.fa.fa-yen.fa-4x]
        [:i.fa.fa-th-list.fa-5x]
        [:div.lead
         "Information underload, no transactions are added."
         [:br]
         [:br]
         "Start tracking your money and "
         [:a.link
          {:on-click #(om/update-state! this assoc :add-transaction? true)}
          "add a transaction"]
         "."]]]))

  (render-filters [this]
    (let [{:keys [computed/tag-filter-on-change
                  computed/amount-filter-on-change
                  computed/date-range-picker-on-apply]} (om/get-state this)
          {:keys [filter/include-tags] :as filters} (:filter (om/get-params this))]
      (debug "render-filters, params: " (om/get-params this))
      (html
        [:div.transaction-filters
         (opts {:style {:padding "1em 0"}})
         [:div.row.expanded
          [:div.columns.small-3
           (filter/->TagFilter (om/computed {:tags include-tags}
                                            {:on-change tag-filter-on-change}))]
          [:div.columns.small-3
           (let [range (date-range-from-filter filters)]
             (->DateRangePicker (om/computed range
                                             {:on-apply date-range-picker-on-apply})))]
          [:div.columns.small-6
           (filter/->AmountFilter (om/computed {:amount-filter (select-keys filters [:filter/min-amount :filter/max-amount])}
                                               {:on-change amount-filter-on-change}))]]])))

  (render-transaction-list [this transactions]
    (let [{currencies      :query/all-currencies
           user            :query/current-user
           all-tags :query/all-tags} (om/props this)
          {:keys [computed/transaction-on-tag-click
                  computed/infinite-scroll-node-fn]} (om/get-state this)]
      (html
        [:div

         ;(.render-filters this)
         [:div.content-section
          [:div.row.column.collapse
           [:div.transactions-container
            [:div.transactions-header.row.align-middle.collapse.is-collapse-child
             ;[] :div.row.collapse.expanded
             [:div.amount
              (str "Amount (" (:currency/code (:user/currency user)) ")")]
             [:div.date
              "Date"]
             [:div.category
              "Category"]
             [:div.note
              "Note"]
             [:div.tags
              "Tags"]
             [:div.local-amount.text-right
              "Amount"]
             [:div.currency
              "Currency"]]
            (if (seq transactions)
              [:ul.transactions-list
               (into [] (comp (map (fn [props]
                                     (->Transaction
                                       (om/computed props
                                                    {:user         user
                                                     :currencies   currencies
                                                     :on-tag-click transaction-on-tag-click
                                                     :all-tags     all-tags}))))
                              (take 50))
                     transactions)]
              [:div.empty-message
               [:div.lead
                [:i.fa.fa-search.fa-fw]
                "No transactions found with filters."]])]]]])))

  (render [this]
    (let [{transactions    :query/transactions
           add-transaction :proxy/add-transaction} (om/props this)
          {:keys [add-transaction?]} (om/get-state this)]
      (html
        [:div#txs
         (if (or (seq transactions)
                 (.has-filter this))
           (.render-transaction-list this transactions)
           (.render-empty-message this))
         (when add-transaction?
           (let [on-close #(om/update-state! this assoc :add-transaction? false)]
             (utils/modal {:content  (->AddTransaction (om/computed add-transaction {:on-close on-close}))
                           :on-close on-close})))]))))

(def ->AllTransactions (om/factory AllTransactions))