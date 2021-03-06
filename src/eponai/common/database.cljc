(ns eponai.common.database
  (:require
    [taoensso.timbre :refer [error debug trace info warn]]
    [clojure.string :as str]
    [clojure.set :as set]
    [clojure.walk :as walk]
    [datascript.db]
    [om.next :as om]
    #?(:clj
    [datomic.api :as datomic])
    [datascript.core :as datascript]
    [inflections.core :as inflections]
    [medley.core :as medley]
    [eponai.common :as c])
  #?(:clj
     (:import [clojure.lang ExceptionInfo]
              [datomic Connection]
              [datomic.db Db]
              [datascript.db DB FilteredDB])))

;; Defines common apis for datascript and datomic

(defprotocol ConnectionApi
  (db* [conn]))

(defprotocol DatabaseApi
  (q* [db query args])
  (entity* [db eid])
  (pull* [db pattern eid])
  (pull-many* [db pattern eids])
  (datoms* [db index args])
  (fulltext-search [db where-clauses fulltext-args]))

(defprotocol TransactApi
  (transact* [conn txs]))

;; Implement the protocols

(extend-protocol ConnectionApi
  #?@(:clj [Connection
            (db* [conn] (datomic/db conn))
            clojure.lang.Atom
            (db* [conn] (datascript/db conn))]
      :cljs [Atom
             (db* [conn] (datascript/db conn))]))

(declare do-pull)

(defn- singularize
  ([s join-words] (singularize s join-words identity))
  ([s join-words term-fn]
   (let [singular (inflections/singular s)]
     (cond
       (empty? singular)
       (term-fn s)
       ;; If there's a singular version of s, it's not empty and
       ;; s starts with it, then we can replace s with the singular version.
       (str/starts-with? s singular)
       (term-fn singular)
       ;; Otherwise, join both.
       :else
       (join-words (term-fn s) (term-fn singular))))))

(def allowed-special-chars #{\space \. \- \,})

(defn sanitized-needles
  "returning multiple needles, split by spaces."
  [needle]
  (sequence
    (comp
      (map #(if (or (contains? allowed-special-chars %)
                    #?(:clj (Character/isLetterOrDigit ^char %)
                       :cljs (re-find #"[a-zA-Z0-9]" (str %))))
              %
              \space))
      (partition-by #(= % \space))
      (remove #(= (first %) \space))
      (map #(apply str %)))
    needle))

(defn datomic-needle-fn [raw-needle]
  ;; Takes a needle and returns a datomic search
  #?(:clj
     (->> (sanitized-needles raw-needle)
          (map #(singularize %
                             (fn [s singular]
                               (str "(" s " OR " singular ")"))
                             (fn [term]
                               (str term "*"))))
          (str/join " AND "))))

(declare merge-query)

(defn- datomic-fulltext [query {:keys [db attr arg return]}]
  (let [polished-needle '?eponai-db-fulltext-datomic-needle]
    ;; Adds where-clauses first as the search will return all
    ;; the entities it matches anyway.
    (merge-query
      {:where [[`(datomic-needle-fn ~arg) polished-needle]
               [(list 'fulltext db attr polished-needle) return]]}
      query)))

(defn datascript-find-fn [raw-needle]
  (let [needles (into []
                      (comp
                        (map #(singularize % (fn [s singular] (str s "|" singular))))
                        (map #(re-pattern (str "(?i)" %))))
                      (sanitized-needles raw-needle))]
    (fn [haystack]
      (every? #(re-find % haystack) needles))))


(defn- datascript-fulltext [query {:keys [db attr arg return]}]
  (let [[[eid value tx score]] return
        val-sym (or value '?val)
        find-needle-fn (atom nil)
        find-fn (fn [haystack needle]
                  (when-not @find-needle-fn
                    (reset! find-needle-fn (datascript-find-fn needle)))
                  (@find-needle-fn haystack))]
    ;; Adds where-clauses last because we want to filter all we can
    ;; before we start scanning the [e a v]s
    (merge-query
      query
      {:where   [[db eid attr val-sym]
                 `[(~'?eponai.db.fulltext/includes-fn ~val-sym ~arg)]]
       :symbols {'?eponai.db.fulltext/includes-fn find-fn}})))

(defn- datascript-datoms [db index args]
  ;; The :vaet index isn't implemented for datascript (who knew!?)
  ;; so this patches it up a little
  (if-not (= :vaet index)
    (apply datascript/datoms db index args)
    (let [[value attr eid tx] args
          val= (fn [{:keys [v]}] (= value v))
          ref? (fn [{:keys [a]}] (= :db.type/ref (get-in (:schema db) [a :db/valueType])))]
      (condp = (count args)
        0 (filter ref? (datascript/datoms db :eavt))
        ;; value
        1 (sequence (comp (filter ref?) (filter val=)) (datascript/datoms db :eavt))
        ;; value attr
        2 (filter val= (datascript/datoms db :aevt attr))
        ;; value attr eid
        3 (datascript/datoms db :eavt eid attr value)
        4 (datascript/datoms db :eavt eid attr value tx)))))

(extend-protocol DatabaseApi
  #?@(:clj  [Db
             (q* [db query args] (apply datomic/q query db args))
             (entity* [db eid] (datomic/entity db eid))
             (pull* [db pattern eid] (do-pull datomic/pull db pattern eid))
             (pull-many* [db pattern eids] (do-pull datomic/pull-many db pattern eids))
             (datoms* [db index args] (apply datomic/datoms db index args))
             (fulltext-search [_ query fulltext]
               (datomic-fulltext query fulltext))
             DB
             (q* [db query args] (apply datascript/q query db args))
             (entity* [db eid] (datascript/entity db eid))
             (pull* [db pattern eid] (do-pull datascript/pull db pattern eid))
             (pull-many* [db pattern eids] (do-pull datascript/pull-many db pattern eids))
             (datoms* [db index args] (datascript-datoms db index args))
             (fulltext-search [_ query fulltext]
               (datascript-fulltext query fulltext))
             FilteredDB
             (q* [db query args] (apply datascript/q query db args))
             (entity* [db eid] (datascript/entity db eid))
             (pull* [db pattern eid] (do-pull datascript/pull db pattern eid))
             (pull-many* [db pattern eids] (do-pull datascript/pull-many db pattern eids))
             (datoms* [db index args] (datascript-datoms db index args))
             (fulltext-search [_ query fulltext]
               (datascript-fulltext query fulltext))]
      :cljs [datascript.db/DB
             (q* [db query args] (apply datascript/q query db args))
             (entity* [db eid] (datascript/entity db eid))
             (pull* [db pattern eid] (do-pull datascript/pull db pattern eid))
             (pull-many* [db pattern eids] (do-pull datascript/pull-many db pattern eids))
             (datoms* [db index args] (datascript-datoms db index args))
             (fulltext-search [_ query fulltext]
                              (datascript-fulltext query fulltext))
             datascript.db/FilteredDB
             (q* [db query args] (apply datascript/q query db args))
             (entity* [db eid] (datascript/entity db eid))
             (pull* [db pattern eid] (do-pull datascript/pull db pattern eid))
             (pull-many* [db pattern eids] (do-pull datascript/pull-many db pattern eids))
             (datoms* [db index args] (datascript-datoms db index args))
             (fulltext-search [_ query fulltext]
                              (datascript-fulltext query fulltext))]))

(declare convert-datomic-ids)

(extend-protocol TransactApi
  #?@(:clj  [Connection
             (transact* [conn txs] (datomic/transact conn txs))
             clojure.lang.Atom
             (transact* [conn txs]
               ;; Convert datomic id's to datascript ids when running jvmclient.
               (datascript/transact conn (convert-datomic-ids txs)))]
      :cljs [Atom
             (transact* [conn txs] (datascript/transact conn txs))]))

(defn database? [db]
  (satisfies? DatabaseApi db))

(defn connection? [conn]
  (satisfies? ConnectionApi conn))

(defn- throw-error [e cause data]
  (let [#?@(:clj  [msg (.getMessage e)]
            :cljs [msg (.-message e)])]
    (throw (ex-info msg
                    {:cause     cause
                     :data      data
                     :message   msg
                     :exception e
                     #?@(:clj [:status :eponai.server.http/service-unavailable])}))))

(defn- do-pull [pull-fn db pattern ents]
  {:pre [(fn? pull-fn)
         (database? db)
         (vector? pattern)]}
  (try
    (let [ret (pull-fn db pattern ents)]
      (if-not (= {:db/id nil}
                 ret)
        ;; Datomic returns {:db/id nil} if there's noting found for a lookup ref for example... so just return nil in that case.
        ret
        nil))
    (catch #?(:clj Exception :cljs :default) e
      (throw-error e ::pull-error {:pattern pattern
                                   :eid     ents}))))

(defn db [conn]
  (db* conn))

(defn q [query db & inputs]
  (try
    (q* db query inputs)
    (catch #?(:clj Exception :cljs :default) e
      (throw-error e ::query-error {:query  query
                                    :inputs inputs}))))

(defn pull-many [db pattern eids]
  (pull-many* db pattern eids))

(defn pull [db pattern eid]
  (when eid
    (pull* db pattern eid)))

(defn entity [db eid]
  (entity* db eid))

(defn datoms [db index & args]
  (datoms* db index args))

(defn- where->query [where-clauses find-pattern symbols]
  {:pre [(vector? find-pattern)]}
  {:find  find-pattern
   :in    (into '[$] symbols)
   :where where-clauses})

(defn- add-fulltext-search [db fulltext where symbols]
  (let [symbols (transduce (map :symbols) into symbols fulltext)
        fulltext-query (reduce (fn [query fulltext-entry]
                                 (fulltext-search db
                                                  query
                                                  (cond-> fulltext-entry
                                                          (nil? (:db fulltext-entry))
                                                          (assoc :db '$))))
                               {:where where :symbols symbols}
                               fulltext)]
    fulltext-query))

(defn- validate-symbols [symbols]
  (letfn [(invalid-symbols [symbols]
            (into []
                  (remove #(or (= '... %) (#{\? \$} (first (or (namespace %) (name %))))))
                  (flatten symbols)))]
    (when-let [invalid-syms (seq (invalid-symbols (keys symbols)))]
      (throw (ex-info (str "Found in valid symbols in query:" (vec invalid-syms))
                      {:symbols symbols
                       :invalid invalid-syms})))))

(defn- x-with
  ([db entity-query] (x-with db entity-query nil))
  ([db {:keys [fulltext find where with symbols rules] :as entity-query} find-pattern]
   {:pre [(database? db)
          (or (vector? where)
              (seq? where)
              (and (nil? where) (contains? symbols '?e)))
          (or (nil? symbols) (map? symbols))
          (or find-pattern find)
          (or (nil? with) (every? symbol? with))]}
   (when (and (some? find) (some? find-pattern)
              (not= find find-pattern))
     (warn "x-with called with both find and find-pattern, and they"
           " are not equal. :find: " find " find-pattern: " find-pattern
           ". Use (find-with ...) instead of (all-with ...)"
           "or (one-with ...) when supplying your own :find."))
   (let [{:keys [where symbols]} (add-fulltext-search db fulltext where symbols)
         find-pattern (or find find-pattern)
         _ (validate-symbols symbols)
         symbol-seq (seq symbols)
         _ (trace "entity-query: " entity-query)
         query (where->query where
                             find-pattern
                             (cond->> (map first symbol-seq)
                                      (seq rules)
                                      (cons '%)))
         rules (not-empty (into [] (mapcat #(%)) (set rules)))
         query (cond-> query
                       (seq with)
                       (assoc :with with)
                       (seq rules)
                       (assoc :rules rules))
         _ (trace "query expanded: " query)
         ret (apply q query
                    db
                    (cond->> (map second symbol-seq)
                             (seq rules)
                             (cons rules)))]
     (trace "query returned: " ret)
     ret)))

(defn lookup-entity
  "Pull full entity with for the specified lookup ref. (Needs to be a unique attribute in lookup ref).

  Returns entity matching the lookupref, (nil if no lookup ref is provided or no entity exists)."
  [db lookup-ref]
  {:pre [(database? db)]}
  (when lookup-ref
    (try
      (entity* db (:db/id (pull db [:db/id] lookup-ref)))
      (catch #?@(:clj [Throwable e] :cljs [:default e])
             nil))))

(defn one-with
  "Used the same way as all-with. Returns one entity id."
  [db params]
  {:pre [(database? db)
         (map? params)]}
  (x-with db params '[?e .]))

(defn all-with
  "takes the database and a map with :where and :symbols keys.

  The value of :where is where-clauses. Ex: '[[?e :project/uuid]]

  The value of :symbols is a map of symbols in the query and
  their values. Ex: {'?uuid user-uuid}

  Returns all entities matching the symbol ?e."
  [db params]
  {:pre [(database? db)
         (map? params)]}
  (x-with db params '[[?e ...]]))

(defn find-with
  "Like one-with and all-with but requires to pass
  it's own find-pattern"
  [db params]
  (assert (some? (:find params))
          (str "No find-pattern for query: " params))
  (x-with db params))

(def into-vec (fnil into []))

(defn merge-query
  "Preforms a merge of two query maps with :where and :symbols."
  [base {:keys [fulltext find where symbols rules with] :as addition}]
  {:pre [(map? base) (map? addition)]}
  (cond-> base
          (seq where)
          (update :where into-vec where)
          (seq symbols)
          (update :symbols merge symbols)
          (some? find)
          (assoc :find find)
          (some? rules)
          (update :rules into-vec rules)
          (some? fulltext)
          (update :fulltext into-vec fulltext)
          (some? with)
          (update :with into-vec with)))

;; Common usages:

(defn pull-one-with [db pattern params]
  (pull db pattern (one-with db params)))

(defn pull-all-with [db pattern params]
  (pull-many db pattern (all-with db params)))

(defn tempid [partition & [n]]
  #?(:clj (apply datomic/tempid (cond-> [partition] (some? n) (conj n)))
     :cljs (apply datascript/tempid (cond-> [partition] (some? n) (conj n)))))

#?(:clj
   (def tempid-type (type (tempid :db.part/user))))

(defn tempid? [x]
  #?(:clj (= tempid-type (type x))
     :cljs (and (number? x) (neg? x))))

(defn dbid? [x]
  #?(:clj  (or (tempid? x) (number? x))
     :cljs (number? x)))

(defn squuid []
  ;; Works for both datomic and datascript.
  (datascript/squuid))


(defn min-by [db k params]
  (some->> (all-with db params)
           (pull-many db [:db/id k])
           seq
           (apply min-key k)
           :db/id))

(defn rename-symbols
  "Takes a query map with :where clauses. Renames each where clause's symbols
    with matches from the renames map."
  [query-map renames]
  {:pre [(map? query-map) (map? renames)]}
  (-> query-map
      (update :symbols set/rename-keys renames)
      (update :where #(walk/postwalk-replace renames %))))

;;;;;;;;;;;;;;
;; Transact

(def ^:dynamic *tx-meta* nil)

(defn transact
  "Transact a collecion of entites into datomic.
  Throws ExceptionInfo if transaction failed."
  [conn txs]
  (let [txs (cond-> txs
                    (some? *tx-meta*)
                    (conj (do (assert (map? *tx-meta*)
                                      (str "*tx-meta* was not a map. was: " *tx-meta*))
                              (assoc *tx-meta* :db/id (tempid :db.part/tx)))))]
    (try
      (trace "Transacting: " txs)
      (let [ret @(transact* conn txs)]
        ret)
      (catch #?@(:clj [Exception e] :cljs [:default e])
             (let [#?@(:clj  [msg (.getMessage e)]
                       :cljs [msg (.-message e)])]
               (error e)
               (throw (ex-info msg
                               {:cause     ::transaction-error
                                :data      {:conn conn
                                            :txs  txs}
                                :message   msg
                                :exception e
                                #?@(:clj [:status :eponai.server.http/service-unavailable])})))))))

(defn transact-map
  "Transact a map into datomic, where the keys names the entities to be transacted for developer convenience.

  Will call transact on (vals m)."
  [conn m]
  (transact conn (vals m)))

(defn transact-one
  "Transact a single entity or transaction into datomic"
  [conn value]
  (transact conn [value]))


;; Datomic to datascript temp-id conversion for jvmclient

#?(:clj
   (def datomic-tempid-type (type (datomic/tempid :db.part/user))))
#?(:clj
   (def datomic-tempid-keys (set (keys (datomic/tempid :db.part/user)))))

#?(:clj
   (defn datomic-id->datascript-id [tempid]
     (assert (= #{:part :idx} datomic-tempid-keys)
             (str "Implementation of datomic tempid has changed."
                  " Keys are now: " datomic-tempid-type))
     (datascript/tempid (:part tempid))))

#?(:clj
   (defn convert-datomic-ids
     ([txs] (convert-datomic-ids txs (memoize datomic-id->datascript-id)))
     ([txs datomic->ds-fn]
      (->> txs
           (walk/postwalk #(cond-> %
                                   (instance? datomic-tempid-type %)
                                   (datomic->ds-fn)))
           (into [])))))

;; Helper fn

(defn singleton-value
  "Given a :ui.singleton.<singleton>/<key>, returns the value by looking up the
  singleton by naming convention."
  [db singleton-key]
  (let [singleton-key-ns (namespace singleton-key)
        last-dot (str/last-index-of singleton-key-ns \.)
        singleton (keyword (subs singleton-key-ns 0 last-dot)
                           (subs singleton-key-ns (inc last-dot) (count singleton-key-ns)))
        singleton-entity (lookup-entity db [:ui/singleton singleton])]
    (when (nil? singleton-entity)
      (warn "Singleton was nil for singleton-key: " singleton-key " singleton: " singleton))
    (get singleton-entity singleton-key)))

(defn to-db
  "Transforms, components, reconcilers or connections to a database."
  [x]
  {:pre [(or (om/component? x) (om/reconciler? x) (connection? x) (database? x))]}
  (reduce (fn [x [pred f]] (cond-> x (pred x) (f)))
          x
          [[om/component? om/get-reconciler]
           [om/reconciler? om/app-state]
           [connection? db]]))

(defn checked-retract-entity
  "Like [:db.fn/retractEntity <e>] but it only retracts parent datoms
  that matches parent-attr."
  [db e parent-attr]
  (let [e-datoms (datoms db :eavt e)
        v-datoms (mapcat (fn [[e]]
                           (datoms db :avet parent-attr e))
                         e-datoms)
        retract-xf (map (fn [[e a v]]
                          [:db/retract e a v]))]
    (-> []
        (into retract-xf e-datoms)
        (into retract-xf v-datoms))))

(defn store-id->dbid [x store-id]
  (let [db (to-db x)]
    (let [store (or (lookup-entity db [:store/username store-id])
                    (lookup-entity db (c/parse-long-safe store-id)))]
      (:db/id store))))