(ns eponai.common.ui.elements.photo
  (:require
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.dom :as dom]
    [taoensso.timbre :refer [debug error warn]]))

;; Element helper functions
(defn- photo* [{:keys [src classes]} & content]
  (if-not (string? src)
    (warn "Ignoring invalid photo src type, expecting a URL string. Got src: " src)
    (apply dom/div
           {:classes (conj classes ::css/photo)
            :style   {:backgroundImage (str "url(" src ")")}}
           (dom/img {:src src})
           content)))

(defn- photo-container [opts & content]
  (dom/div (css/add-class ::css/photo-container opts) content))

;; Functiosn for creating elements in the UI
(defn photo [opts]
  (photo-container
    nil
    (photo* opts)))

(defn square [opts]
  (photo-container
    nil
    (photo* (css/add-class ::css/photo-square opts))))

(defn circle [opts & content]
  (photo-container
    {:classes [::css/photo-circle]}
    (photo* (css/add-class ::css/photo-square opts))
    content))

(defn thumbail [opts]
  (photo-container
    nil
    (photo* (->> opts
                 (css/add-class ::css/photo-square)
                 (css/add-class ::css/photo-thumbnail)))))

(defn overlay [opts & content]
  (dom/div
    (css/add-class ::css/overlay opts)
    (dom/div
      (css/add-class ::css/photo-overlay-content)
      content)))

(defn with-overlay [opts photo-element & content]
  (dom/div
    (css/add-class ::css/overlay-container)
    photo-element
    (dom/div
      (css/add-class ::css/photo-overlay)
      (apply dom/div (css/add-class ::css/photo-overlay-content opts) content))))

(defn header [{:keys [src] :as opts} & content]
  (when-not (string? src)
    (error "Invalid photo URL type. Cover expects URL string as first argument. Got URL: " src))
  ;(photo-container
  ;  nil)
  (apply photo* (css/add-class ::css/photo-header opts)
         content))

(defn cover [{:keys [src] :as opts} & content]
  (when-not (string? src)
    (error "Invalid photo URL type. Cover expects URL string as first argument. Got URL: " src))
  ;(photo-container
  ;  nil)
  (apply photo* (css/add-class ::css/photo-cover opts)
         content))

(defn collage [{:keys [srcs]}]
  (when-not (every? string? srcs)
    (error "Invalid photo URL type. Collage expects collection of URL strings. Got URLs: " srcs))
  (apply photo-container
         {:classes [::css/photo-collage]}
         (mapcat
           (fn [[large mini-1 mini-2]]
             [(photo* {:src     large
                       :classes [::css/photo-square]})
              (photo-container nil
                               (photo* {:src mini-1
                                        :classes [::css/photo-square]})
                               (photo* {:src mini-2
                                        :classes [::css/photo-square]}))])
                 (partition 3 srcs))))

(defn store-photo [store]
  (let [default-src "/assets/img/storefront.jpg"
        photo-src (get-in store [:store/profile :store.profile/photo :photo/path] default-src)]
    (circle {:src photo-src
             :classes [:store-photo]})))

(defn user-photo [{:keys [user] :as opts}  & content]
  (let [default-src "/assets/img/storefront.jpg"
        photo-src (get-in user [:user/profile :user.profile/photo :photo/path] default-src)]
    (dom/div
      (css/add-class :user-profile-photo (dissoc opts :user))
      (circle {:src     photo-src
               :classes [:user-photo]}
              content))))

(defn product-photo [photo]
  (let [default-src "/assets/img/storefront.jpg"
        photo-src (:photo/path photo default-src)]
    (square {:src photo-src})))