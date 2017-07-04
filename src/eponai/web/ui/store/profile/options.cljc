(ns eponai.web.ui.store.profile.options
  (:require
    [om.next :as om :refer [defui]]
    [eponai.common.ui.dom :as dom]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.common.ui.elements.menu :as menu]
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.web.ui.switch-input :as switch]
    [eponai.web.ui.button :as button]
    [eponai.client.routes :as routes]
    [taoensso.timbre :refer [debug]]
    [eponai.client.parser.message :as msg]
    [eponai.common.ui.common :as common]
    [clojure.string :as string]
    [cemerick.url :as url]))

(defn store-missing-information [store]
  (let [{:store/keys [items profile]} store]
    (into {} (filter #(false? (val %)) {:has-items? (pos? (count items))
                                        :has-photo? (some? (:store.profile/photo profile))}))))

(defn store-has-never-been-open? [store]
  (nil? (get-in store [:store/status :status/type])))

(defn store-is-active-in-stripe? [store]
  (= (get-in store [:store/stripe :stripe/status :status/type])
     :status.type/active))

(defn store-is-open? [store]
  (= (get-in store [:store/status :status/type])
     :status.type/open))

(defn store-is-closed? [store]
  (= (get-in store [:store/status :status/type])
     :status.type/closed))

(defn store-has-shipping? [store]
  (pos? (count (get-in store [:store/shipping :shipping/rules]))))

(defn stripe-need-more-info? [store]
  (not-empty (store-missing-information store)))

(defn store-can-open? [store]
  (and (store-is-active-in-stripe? store)
       (store-has-shipping? store)))

(defn render-status [component store]
  (let [{:query/keys [current-route]} (om/props component)
        {:keys [route-params]} current-route
        missing-info (store-missing-information store)

        closed-info [(dom/label nil "Your store is CLOSED")
                     (dom/p nil (dom/small nil "Your store is only visible to you. People who try to view your store or one of your products see a page not found error."))]]
    (dom/div
      nil
      (if (store-is-open? store)
        ;; Store status is set to OPEN. We need to check that store is still valid in Stripe
        ;; as they can update the account status when they need more info
        (cond
          ;; Stripe has disabled the account. Purchases should be disabled, so show store as CLOSED.
          (not (store-is-active-in-stripe? store))
          closed-info

          ;; We have not provided some information like photos and products yet,
          ;; store should be UNLISTED but still enabled for purchases.
          (not-empty missing-info)
          [(dom/label nil "Your store is OPEN UNLISTED")
           (dom/p nil
                  (dom/small nil "It seems there's some information missing for your store to be visible to the public. Customers that find your store can still make purchases, but its products and streams do not appear in search results. ")
                  (dom/br nil)
                  (dom/strong nil (dom/small nil "Follow the steps below to make your store appear in search:")))
           ;; If the store is unlisted, show the owner what information is missing to appear in search.
           (dom/div
             (css/add-class :store-status-reasons)
             (when (some? (:has-photo? missing-info))
               (button/user-setting-default
                 {:href    (routes/url :store-dashboard/profile route-params)
                  :classes [:small]}
                 (dom/span nil "Upload store photo")))
             (when (some? (:has-items? missing-info))
               (button/user-setting-default
                 {:href    (routes/url :store-dashboard/create-product route-params)
                  :classes [:small]}
                 (dom/span nil "Add your first product"))))]

          ;; If nothing else, we're just a normal OPEN store
          :else
          [(dom/label nil "Your store is OPEN")
           (dom/p nil (dom/small nil "Customers can make purchases at your store and find your products and streams in search results."))])

        ;; Store status is set to CLOSED. We only want to let the store owner open the
        ;; account when stripe account is active.
        (cond
          ;; CHeck that we've verified the Stripe account enough
          (store-is-active-in-stripe? store)
          (if (store-has-never-been-open? store)
            ;; If store has never been open, show a special message to the store owner.
            [(dom/label nil "Your store is ready to open")
             (dom/p nil (dom/small nil "Open your store to make your products appear in search results and let customers make purchases."))]
            closed-info)

          ;; If nothing else, show that store is CLOSED
          :else
          closed-info)))))


(defn status-button [component store]
  (let [store-is-open-and-active? (and (store-is-open? store) (store-is-active-in-stripe? store))]
    (button/store-navigation-cta
      (cond->> {:onClick (if store-is-open-and-active?
                           #(om/update-state! component assoc :modal :modal/close-store)
                           #(.open-store component))}

               (not (store-can-open? store))
               (css/add-class :disabled)

               store-is-open-and-active?
               (css/add-class :hollow))
      (if store-is-open-and-active?
        (dom/span nil "Close store")
        (dom/span nil "Open store")))))

(defn close-store-modal [component]
  (let [on-close #(om/update-state! component dissoc :modal)]
    (common/modal
      {:on-close on-close}
      (dom/p (css/add-class :header) "Do you want to close your store?")
      (dom/p nil (dom/small nil "Your store will only be visible to you. Customers who try to view your store or one of your products will see a page not found error."))
      (dom/p nil (dom/small nil "You can reopen your store any time."))
      (dom/div
        (css/add-class :action-buttons)
        (button/user-setting-default
          {:onClick on-close}
          (dom/span nil "Cancel"))
        (button/user-setting-cta
          {:onClick #(do (.close-store component)
                         (on-close))}
          (dom/span nil "Yes, close store"))))))

(defn delete-store-modal [component]
  (let [on-close #(om/update-state! component dissoc :modal)]
    (common/modal
      {:on-close on-close}
      (dom/p (css/add-class :header) "Do you want to permantly delete your store?")
      (dom/p nil (dom/small nil "This is a permanent action. Once confirmed, you will not be able to restore your settings in the future. Want to hit pause? Close your store temporarily instead."))
      (dom/div
        (css/add-class :action-buttons)
        (button/user-setting-default
          {:onClick on-close}
          (dom/span nil "Cancel"))
        (button/default-hollow
          (->>
            {:onClick #(do (.delete-store component)
                           (on-close))}
            (css/add-classes [:small :alert]))
          (dom/span nil "Yes, delete store"))))))

(defn username-modal [component]
  (let [on-close #(om/update-state! component dissoc :modal)
        {:keys [input-username error-message]} (om/get-state component)
        {:query/keys [store]} (om/props component)
        url-name #(url/url-encode (string/lower-case %))]
    (common/modal
      {:on-close on-close
       :classes  [:store-username-modal]
       :size     "tiny"}
      (dom/p (css/add-class :header) "Change store username")
      (dom/p nil (dom/small nil "Your store username is the same as your store address."))
      ;(dom/p nil )
      (dom/p nil
             (dom/span (css/add-class :host) "sulo.live/store/")
             (dom/strong nil (or input-username (:store/username store ""))))
      (dom/div
        (css/add-class :username-input)

        (dom/input {:type        "text"
                    :value       (or input-username (:store/username store ""))
                    :placeholder (url-name (string/join (remove string/blank? (get-in store [:store/profile :store.profile/name]))))
                    :onChange    #(om/update-state! component assoc :input-username (url-name (.-value (.-target %))))}))
      (dom/p (css/add-class :text-alert) (dom/small nil (:message error-message)))
      (dom/div
        (css/add-class :action-buttons)
        (button/cancel
          {:onClick on-close})
        (button/save
          {:onClick #(.save-username component)})))))

(defui StoreStatus
  static om/IQuery
  (query [_]
    [{:query/store [
                    {:store/status [:status/type]}
                    {:store/profile [:store.profile/photo
                                     :store.profile/cover
                                     :store.profile/email]}
                    {:store/shipping [:shipping/rules]}
                    :store/username
                    :store/items
                    {:store/stripe [{:stripe/status [:status/type]}]}]}
     {:query/stripe-account [:stripe/details-submitted?
                             :stripe/charges-enabled?
                             :stripe/payouts-enabled?
                             :stripe/verification]}
     :query/current-route
     :query/messages
     :query/locations])
  Object
  (open-store [this]
    (let [{:query/keys [store stripe-account current-route]} (om/props this)]
      (when (store-can-open? store)
        (msg/om-transact! this [(list 'store/update-status {:status   {:status/type :status.type/open}
                                                            :store-id (:db/id store)})
                                :query/store])
        (debug "Open store"))))
  (close-store [this]
    (let [{:query/keys [store stripe-account current-route]} (om/props this)]
      (om/update-state! this dissoc :modal)
      (msg/om-transact! this [(list 'store/update-status {:status   {:status/type :status.type/closed}
                                                          :store-id (:db/id store)})
                              :query/store])
      (debug "Close store")))

  (delete-store [this]
    (let [{:query/keys [store]} (om/props this)]
      (msg/om-transact! this [(list 'store/delete {:status   {:status/type :status.type/closed}
                                                   :store-id (:db/id store)})])))

  (save-username [this]
    (let [{:keys [input-username]} (om/get-state this)
          {:query/keys [store]} (om/props this)]
      (msg/om-transact! this [(list 'store/update-username {:store-id (:db/id store)
                                                            :username input-username})
                              :query/store])))

  (componentDidUpdate [this _ _]
    (let [{:query/keys [current-route locations]} (om/props this)
          username-msg (msg/last-message this 'store/update-username)
          delete-msg (msg/last-message this 'store/delete)]
      (when (msg/final? username-msg)
        (msg/clear-messages! this 'store/update-username)
        (if (msg/success? username-msg)
          (do
            ;(debug "New store success: " (msg/message username-msg))
            (routes/set-url! this (:route current-route) (assoc (:route-params current-route) :store-id (:username (msg/message username-msg))))
            (om/update-state! this dissoc :modal :error-message))
          (om/update-state! this assoc :error-message (msg/message username-msg))))

      (when (msg/final? delete-msg)
        (msg/clear-messages! this 'store/delete)
        (if (msg/success? delete-msg)
          (if (some? locations)
            (routes/set-url! this :index {:locality (:sulo-locality/path locations)})
            (routes/set-url! this :landing-page))))))

  (is-loading? [this]
    (let [username-msg (msg/last-message this 'store/update-username)
          delete-msg (msg/last-message this 'store/delete)]
      (msg/pending? delete-msg)))

  (render [this]
    (let [{:query/keys [store stripe-account current-route]} (om/props this)
          {:keys [route-params]} current-route
          store-status (get-in store [:store/status :status/type])
          {:stripe/keys [charges-enabled? payouts-enabled? verification]} stripe-account
          {:stripe.verification/keys [due-by fields-needed disabled-reason]} verification
          {:keys [modal input-username]} (om/get-state this)

          stripe-status (get-in store [:store/stripe :stripe/status :status/type])]
      (debug "Status props: " (om/props this))
      (dom/div

        {:id "sulo-store-info-status"}

        (when (.is-loading? this)
          (common/loading-spinner nil))

        (cond (= modal :modal/close-store)
              (close-store-modal this)
              (= modal :modal/username)
              (username-modal this)
              (= modal :modal/delete-store)
              (delete-store-modal this))

        (dom/div
          (css/add-class :section-title)
          (dom/h2 nil "General"))
        (callout/callout
          nil
          (menu/vertical
            (css/add-class :section-list)
            (menu/item
              nil
              (grid/row
                (->> (css/add-class :collapse)
                     (css/align :middle))
                (grid/column
                  nil
                  (dom/label nil "Store username")
                  (dom/p nil (dom/small nil "Your store username is the same as your store address.")
                         ;(dom/br nil)
                         ;(dom/small nil (dom/strong nil input-username))
                         ))
                (grid/column
                  (css/text-align :right)
                  (dom/p nil
                         (dom/small nil "sulo.live/store/ ")
                         (dom/span nil (or (:store/username store)
                                           (:db/id store))))
                  (button/store-setting-default
                    {:onClick #(om/update-state! this assoc :modal :modal/username)}
                    (dom/span nil "Change username")))))))

        (dom/div
          (css/add-class :section-title)
          (dom/h2 nil "Status"))

        (callout/callout
          nil
          (menu/vertical
            (css/add-class :section-list)
            (menu/item
              nil
              (grid/row
                (->> (css/add-class :collapse)
                     (css/align :middle))
                (grid/column
                  (grid/column-size {:small 12})
                  (render-status this store))
                (grid/column
                  (css/text-align :right)
                  ;(status-button this store)
                  )
                ))
            (menu/item
              nil
              (grid/row
                (->> (css/add-class :collapse)
                     (css/align :middle))
                (grid/column
                  (grid/column-size {:small 12 :medium 8})

                  (cond
                    ;; If store is disabled/not verified in Stripe or has no shipping,
                    ;; they should not be able to open their store
                    (not (store-can-open? store))
                    [(dom/label nil "Provide information to open store")
                     (dom/p nil (dom/small nil "Your store will be visible to the public and customers will be able to make purchases. The following information is needed before you can open your store:"))
                     (dom/div
                       (css/add-class :store-status-reasons)

                       ;; Store account is either disabled or has never been verified in Stripe. E.g. accepted terms and provided business info.
                       (when-not (store-is-active-in-stripe? store)
                         (button/user-setting-default
                           {:href    (routes/url :store-dashboard/business#verify route-params)
                            :classes [:small]} (dom/span nil "Verify account")))

                       ;; The store has not provided any shipping rules, they are needed for anyone to be able to shop.
                       (when-not (store-has-shipping? store)
                         (button/user-setting-default
                           {:href    (routes/url :store-dashboard/shipping route-params)
                            :classes [:small]} (dom/span nil "Specify shipping"))))]

                    ;; Store is open so owner should be able to close their store.
                    (store-is-open? store)
                    [(dom/label nil "Close store")
                     (dom/p nil (dom/small nil "Your store will only be visible to you. Customers who try to view your store or one of your products will see a page not found error. You can reopen your store any time."))]

                    ;; If nothing else, store is already closed.
                    :else
                    [(dom/label nil "Open store")
                     (dom/p nil (dom/small nil "Your store will be open to the public and customers will be able to make purchases."))]))
                (grid/column
                  (css/text-align :right)
                  (status-button this store)
                  )
                ))))

        (dom/div
          (css/add-class :section-title)
          (dom/h2 nil "Danger zone"))
        (callout/callout
          nil
          (menu/vertical
            (css/add-class :section-list)
            (menu/item
              nil
              (grid/row
                (->> (css/add-class :collapse)
                     (css/align :middle))
                (grid/column
                  (grid/column-size {:small 12 :medium 8})
                  (dom/label nil (str "Delete " (get-in store [:store/profile :store.profile/name])))
                  (dom/p nil (dom/small nil "Deleting your store is a permanent action. Once confirmed, you will not be able to restore your settings in the future. Want to hit pause? Close your store temporarily instead.")))
                (grid/column
                  (css/text-align :right)
                  (button/default-hollow
                    (css/add-classes [:alert] {:onClick #(om/update-state! this assoc :modal :modal/delete-store)})
                    (dom/span nil "Delete store")))))))
        ))))

(def ->StoreStatus (om/factory StoreStatus))
