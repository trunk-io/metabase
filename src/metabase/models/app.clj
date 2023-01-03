(ns metabase.models.app
  (:require
   [metabase.models.action :as action]
   [metabase.models.interface :as mi]
   [metabase.models.permissions :as perms]
   [metabase.models.query :as query]
   [metabase.models.serialization.hash :as serdes.hash]
   [toucan.db :as db]
   [toucan.models :as models]))

(models/defmodel App :app)

;;; You can read/write an App if you can read/write its Collection
(derive App ::perms/use-parent-collection-perms)

(mi/define-methods
 App
 {:pre-insert (fn [app] (action/check-data-apps-enabled) app)
  :types      (constantly {:options   :json
                           :nav_items :json})
  :properties (constantly {::mi/timestamped? true
                           ::mi/entity-id    true})})

;;; Should not be needed as every app should have an entity_id, but currently it's necessary to satisfy
;;; metabase-enterprise.models.entity-id-test/comprehensive-identity-hash-test.
(defmethod serdes.hash/identity-hash-fields App
  [_app]
  [:entity_id])

(mi/define-batched-hydration-method add-app-id
  :app_id
  "Add `app_id` to Collections that are linked with an App."
  [collections]
  (if-let [coll-ids (seq (into #{}
                               (comp (map :id)
                                     ;; The ID "root" breaks the query.
                                     (filter int?))
                               collections))]
    (let [coll-id->app-id (into {}
                                (map (juxt :collection_id :id))
                                (db/select [App :collection_id :id]
                                  :collection_id [:in coll-ids]))]
      (for [coll collections]
        (let [app-id (coll-id->app-id (:id coll))]
          (cond-> coll
            app-id (assoc :app_id app-id)))))
    collections))

(defn- app-cards [app]
  (->> (db/query {:union
                  [{:select [:c.*]
                    :from [[:report_card :c]]
                    :where [:and
                            [:= :c.collection_id (:collection_id app)]]}
                   {:select [:c.*]
                    :from [[:report_card :c]]
                    :join [[:report_dashboardcard :dc] [:= :dc.card_id :c.id]
                           [:report_dashboard :d] [:= :d.id :dc.dashboard_id]]
                    :where [:and
                            [:= :d.collection_id (:collection_id app)]]}]})
       (db/do-post-select 'Card)))

(defn- referenced-models [cards]
  (when-let [model-ids
             (->> cards
                  (into #{} (mapcat (comp query/collect-card-ids :dataset_query)))
                  not-empty)]
    (db/select 'Card {:where [:and
                              [:in :id model-ids]
                              :dataset]})))

(mi/define-simple-hydration-method add-models
  :models
  "Add the fully hydrated models used by the app."
  [app]
  (let [used-cards (app-cards app)
        contained-models (into #{} (filter :dataset) used-cards)]
    (into contained-models (referenced-models used-cards))))
