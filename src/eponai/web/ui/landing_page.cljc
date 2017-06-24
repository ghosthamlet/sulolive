(ns eponai.web.ui.landing-page
  (:require
    [cemerick.url :as url]
    [clojure.spec :as s]
    [eponai.common.ui.dom :as dom]
    [eponai.common.ui.elements.css :as css]
    [om.next :as om :refer [defui]]
    [eponai.common.ui.router :as router]
    [eponai.common.ui.navbar :as nav]
    [eponai.common.ui.common :as common]
    [taoensso.timbre :refer [debug]]
    [eponai.web.ui.photo :as photo]
    [eponai.common.ui.elements.grid :as grid]
    #?(:cljs [eponai.web.utils :as web-utils])
    [eponai.web.ui.button :as button]
    [eponai.client.auth :as auth]
    [eponai.common.shared :as shared]
    [eponai.client.routes :as routes]
    [eponai.common.ui.icons :as icons]
    [eponai.common.ui.elements.input-validate :as v]
    [eponai.web.social :as social]
    [eponai.client.utils :as client-utils]
    [eponai.client.parser.message :as msg]
    [medley.core :as medley]
    [eponai.web.ui.footer :as foot]))

(def form-inputs
  {:field/email    "field.email"
   :field/location "field.location"})

(s/def :field/email #(client-utils/valid-email? %))
(s/def :field/location (s/and string? #(not-empty %)))
(s/def ::location (s/keys :req [:field/email :field/location]))

(defn top-feature [opts icon title text]
  (grid/column
    (css/add-class :feature-item)
    (grid/row
      (css/add-class :align-middle)
      (grid/column
        (->> (grid/column-size {:small 2 :medium 12})
             (css/text-align :center))
        icon)
      (grid/column
        nil
        (dom/h4 (css/add-class :feature-title) title)
        (dom/p nil text)))))

(defui LandingPage
  static om/IQuery
  (query [_]
    [{:proxy/navbar (om/get-query nav/Navbar)}
     {:proxy/footer (om/get-query foot/Footer)}
     :query/current-route
     {:query/auth [:db/id]}
     {:query/sulo-localities [:sulo-locality/title
                              :sulo-locality/path
                              {:sulo-locality/photo [:photo/id]}]}
     :query/messages])
  Object
  (select-locality [this locality]
    #?(:cljs
       (do
         (web-utils/set-locality locality)
         (om/transact! this [(list 'client/set-locality {:locality locality})
                             :query/locations]))))
  (submit-new-location [this]
    #?(:cljs
       (let [email (web-utils/input-value-by-id (:field/email form-inputs))
             location (web-utils/input-value-by-id (:field/location form-inputs))
             input-map {:field/email email :field/location location}

             validation (v/validate ::location input-map form-inputs)]
         (debug "Input validation: " validation)
         (when (nil? validation)
           (msg/om-transact! this [(list 'location/suggest {:location location :email email})]))
         (om/update-state! this assoc :input-validation validation))))

  (componentDidMount [this]
    (let [{:query/keys [current-route]} (om/props this)]
      #?(:cljs
         (when (= (:route current-route) :landing-page/locality)
           (when-let [locs (web-utils/element-by-id "sulo-locations")]
             (web-utils/scroll-to locs 250))))))

  (componentDidUpdate [this _ _]
    (let [last-message (msg/last-message this 'location/suggest)]
      (when (msg/final? last-message)
        ;(when (msg/success? last-message)
        ;  #?(:cljs
        ;     (do
        ;       (set! (.-value (web-utils/element-by-id (:field/email form-inputs))) "")
        ;       (set! (.-value (web-utils/element-by-id (:field/location form-inputs))) ""))))
        (om/update-state! this assoc :user-message (msg/message last-message)))))
  (render [this]
    (let [{:proxy/keys [navbar footer]
           :query/keys [auth sulo-localities]} (om/props this)
          {:keys [input-validation user-message]} (om/get-state this)
          last-message (msg/last-message this 'location/suggest)]
      (debug "Localitites: " sulo-localities)
      (common/page-container
        {:navbar navbar :footer footer :id "sulo-landing"}
        (photo/cover
          {:photo-id "static/shop"}
          (grid/row-column
            (css/text-align :center)
            (dom/div
              (css/add-class :section-title)
              (dom/h1 nil (dom/span nil "Your local marketplace online")))
            (dom/p nil (dom/span nil "Global change starts local. Shop and hang out LIVE with your favourite local brands and people from your city."))))
        (dom/div
          {:classes ["top-features"]}
          (grid/row
            (grid/columns-in-row {:small 1 :medium 3})
            (top-feature
              nil
              (icons/shopping-bag)
              "Shop and discover"
              "Explore and shop from a marketplace filled with your local gems.")
            (top-feature
              nil
              (icons/video-camera)
              "Watch and chat"
              "Watch live streams while you chat with brands and other users on SULO.")
            (top-feature
              nil
              (icons/heart)
              "Join the community"
              "Sign in to follow stores and be the first to know when they are live.")))

        (dom/div
          (css/text-align :center {:id "sulo-locations"})
          (common/content-section
            nil
            "Where do you shop local?"
            (grid/row
              (->> (grid/columns-in-row {:small 1})
                   (css/add-classes [:locations]))

              (let [loc-vancouver (medley/find-first #(= "yvr" (:sulo-locality/path %)) sulo-localities)
                    loc-montreal (medley/find-first #(= "yul" (:sulo-locality/path %)) sulo-localities)]
                (map (fn [{:sulo-locality/keys [title photo coming-soon] :as loc}]
                       (grid/column
                         nil
                         (dom/a
                           (css/add-class :city-anchor {:onClick #(do
                                                                   (debug "Setting locality! " loc)
                                                                   (.select-locality this loc)
                                                                   (if (nil? auth)
                                                                     (auth/show-lock (shared/by-key this :shared/auth-lock))
                                                                     (routes/set-url! this :index {:locality (:sulo-locality/path loc)})))})
                           (photo/photo
                             {:photo-id       (:photo/id photo)
                              :transformation :transformation/preview}
                             (photo/overlay
                               nil
                               (dom/div
                                 (css/text-align :center)
                                 (dom/strong nil title)
                                 (dom/div
                                   {:classes [:button :hollow]}
                                   (dom/span nil "Enter")))
                               (when (and (not-empty coming-soon) (nil? auth))
                                 (dom/p (css/add-class :coming-soon) (dom/small nil coming-soon))))))))

                     ;; Assoc comming soon mesage for each city, position them in this array to keep order.
                     [(assoc loc-vancouver :sulo-locality/coming-soon "Coming soon - Summer 2017")
                      (assoc loc-montreal :sulo-locality/coming-soon "Coming soon - Fall 2017")]))


              (grid/column
                (css/add-classes [:suggest-location])
                (photo/cover
                  nil
                  (if (msg/final? last-message)
                    (dom/h3 nil (str user-message))
                    [(dom/h3 nil "Local somewhere else? Subscribe to our newsletter and let us know where we should go next!")
                     (dom/form
                       (css/add-class :input-container)
                       (v/input {:type        "text"
                                 :placeholder "Your location"
                                 :id          (:field/location form-inputs)}
                                input-validation)
                       (v/input {:type        "email"
                                 :placeholder "Your email"
                                 :id          (:field/email form-inputs)}
                                input-validation)
                       (dom/button
                         (css/add-class :button {:onClick #(do
                                                            (.preventDefault %)
                                                            (.submit-new-location this))})
                         (dom/span nil "Submit")))
                     (when (msg/pending? last-message)
                       (dom/p (css/add-class :user-message)
                              (dom/small nil (dom/i {:classes ["fa fa-spinner fa-spin"]}))
                              ))])
                  ))
              )
            "")
          )

        (common/sell-on-sulo this)

        (dom/div
          (css/add-class :instagram-feed)
          (common/content-section
            {:href   (:social/instagram social/profiles)
             :target "_blank"}
            "Follow us"
            (dom/div
              (css/add-class "powr-instagram-feed" {:id "0c4b9f24_1497385671"})
              ;(dom/i {:classes ["fa fa-spinner fa-spin"]} )
              )
            "@sulolive on Instagram"))


        ;<div class="powr-instagram-feed" id="0c4b9f24_1497385671"></div>
        ))))

(router/register-component :landing-page LandingPage)