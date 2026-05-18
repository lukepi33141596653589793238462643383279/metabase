(ns metabase.metabot.tools.search
  "Search tool wrappers for Metabot v3."
  (:require
   [clojure.string :as str]
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.metabot.config :as metabot.config]
   [metabase.metabot.scope :as scope]
   [metabase.metabot.search-models :as metabot.search-models]
   [metabase.metabot.tmpl :as te]
   [metabase.metabot.tools.shared :as shared]
   [metabase.metabot.tools.shared.instructions :as instructions]
   [metabase.metabot.tools.shared.llm-representations :as llm-rep]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :as premium-features]
   [metabase.search.core :as search]
   [metabase.search.engine :as search.engine]
   [metabase.transforms.core :as transforms]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private metabot-search-models
  (sorted-set "card" "collection" "dashboard" "database" "dataset" "metric" "table" "transform"))

(def ^:private metabot-weight-overrides
  "Per-request weight overrides applied to every metabot search. Boosts curator signals
   (verified, official-collection) so curated content surfaces ahead of obscure items.
   The LLM has no implicit affordance to 'trust' results otherwise — these signals are
   how a human user would visually distinguish 'safe' content. text/exact stay at the
   defaults (5) so on-topic results still win — curation only breaks near-ties."
  {:official-collection 4
   :verified            5
   :view-count          3})

(defn- postprocess-search-result
  "Transform a single search result to match the appropriate entity-specific schema."
  [{:keys [verified moderated_status collection official_collection] :as result}]
  (let [model (:model result)
        verified? (or (boolean verified) (= moderated_status "verified"))
        official? (boolean official_collection)
        collection-info (select-keys collection [:id :name :authority_level])
        common-fields {:id                  (:id result)
                       :type                (metabot.search-models/search-model->entity-type model)
                       :name                (:name result)
                       :description         (:description result)
                       :updated_at          (:updated_at result)
                       :created_at          (:created_at result)
                       :official_collection official?
                       :verified            verified?}]
    (case model
      "database"
      common-fields

      "table"
      (merge common-fields
             {:name            (:table_name result)
              :display_name    (:name result)
              :database_id     (:database_id result)
              :database_schema (:table_schema result)})

      "dashboard"
      (merge common-fields
             {:collection   collection-info
              :is_container true})

      "collection"
      (merge common-fields
             {:authority_level (:authority_level result)
              :location        (:location result)
              :is_container    true})

      "transform"
      (merge common-fields
             {:database_id (:database_id result)})

      ;; Questions, metrics, and datasets
      (merge common-fields
             {:database_id (:database_id result)
              :collection  collection-info}))))

(defn- enrich-with-collection-descriptions
  "Fetch and merge collection descriptions for all search results that have collection IDs."
  [results]
  (let [coll-ids     (->> results (keep #(get-in % [:collection :id])) distinct)
        descriptions (when (seq coll-ids)
                       (t2/select-pk->fn :description :model/Collection :id [:in coll-ids]))]
    (cond->> results
      (seq descriptions) (mapv (fn [r]
                                 (let [cid (-> r :collection :id)]
                                   (update r :collection m/assoc-some :description (get descriptions cid))))))))

(defn- collection-result?
  "Whether a postprocessed result represents a collection itself (vs. an item *in* a collection)."
  [r]
  (= "collection" (:type r)))

(defn- result-collection-id
  "The collection id this result lives in (or, for collection results, the collection's own id)."
  [r]
  (if (collection-result? r) (:id r) (get-in r [:collection :id])))

(defn- ancestor-ids
  "Parse a Collection :location string like \"/12/34/\" into [12 34]."
  [location]
  (when (and location (not= "/" location))
    (->> (str/split location #"/") (remove str/blank?) (keep parse-long))))

(defn- enrich-with-collection-paths
  "Stamp each result with :collection_path (and :full_path for collection results) and
   :library_member (boolean, gated by the :library premium feature).

   :collection_path is the slash-joined chain of ancestor names ending in the result's
   collection name (e.g. \"Marketing/Q4 Reports/Email\"). For collection-typed results,
   the same string is also exposed as :full_path."
  [results]
  (let [direct-ids   (->> results (keep result-collection-id) distinct)
        ;; Bulk-fetch :location for direct ids so we can chase ancestors.
        direct-locations (when (seq direct-ids)
                           (t2/select-fn-set :location :model/Collection :id [:in direct-ids]))
        ancestor-id-set  (->> direct-locations (mapcat ancestor-ids) (into #{}))
        all-ids          (into (set direct-ids) ancestor-id-set)
        coll-rows    (when (seq all-ids)
                       (t2/select [:model/Collection :id :name :location :personal_owner_id]
                                  :id [:in all-ids]))
        id->row      (into {} (map (juxt :id identity)) coll-rows)
        path-of      (fn [coll-id]
                       (when-let [{:keys [name location]} (get id->row coll-id)]
                         (let [ancestor-names (->> (ancestor-ids location)
                                                   (keep #(get-in id->row [% :name])))]
                           (str/join "/" (concat ancestor-names [name])))))
        library?     (premium-features/has-feature? :library)
        library-of   (fn [coll-id]
                       (boolean (and library?
                                     coll-id
                                     (nil? (get-in id->row [coll-id :personal_owner_id])))))]
    (mapv (fn [r]
            (let [cid  (result-collection-id r)
                  path (when cid (path-of cid))]
              (cond-> r
                path (assoc :collection_path path)
                (and path (collection-result? r)) (assoc :full_path path)
                cid  (assoc :library_member (library-of cid)))))
          results)))

(defn- enrich-with-database-engines
  "Fetch and merge database engine info for search results that have database IDs."
  [results]
  (let [db-ids  (->> results (keep :database_id) distinct)
        engines (when (seq db-ids)
                  (t2/select-pk->fn :engine :model/Database :id [:in db-ids]))]
    (cond->> results
      (seq engines) (mapv (fn [r] (m/assoc-some r :database_engine (get engines (:database_id r))))))))

(defn- remove-unreadable-transforms
  "Remove transforms from search results that the user cannot read.
  This filters out transforms where the user doesn't have access to the source tables/database."
  [results]
  (let [transform-ids (->> results (filter #(= "transform" (:type %))) (map :id) set)
        readable-ids (when (seq transform-ids)
                       (->> (t2/select :model/Transform :id [:in transform-ids])
                            transforms/add-source-readable
                            (filter :source_readable)
                            (map :id)
                            set))]
    (cond->> results
      (seq transform-ids) (filterv (fn [result]
                                     (or (not= "transform" (:type result))
                                         (contains? readable-ids (:id result))))))))

(defn search
  "Search for data sources (tables, models, cards, dashboards, metrics, transforms) in Metabase.

   Routes the query to the semantic engine when available — that engine already does
   hybrid keyword + semantic RRF fusion at the SQL level (see
   `metabase-enterprise.semantic-search.scoring/rrf-rank-exp`). When semantic isn't
   available, falls back to the default keyword engine. No metabot-level fusion is
   needed in either case."
  [{:keys [query database-id collection-id created-at last-edited-at
           entity-types limit metabot-id profile-id search-native-query weights]}]
  (log/infof "[METABOT-SEARCH] Starting search with params: %s"
             {:query               query
              :database-id         database-id
              :created-at          created-at
              :last-edited-at      last-edited-at
              :entity-types        entity-types
              :limit               limit
              :metabot-id          metabot-id
              :profile-id          profile-id
              :search-native-query search-native-query
              :weights             weights})
  (let [search-models   (if (seq entity-types)
                          (set (distinct (keep metabot.search-models/entity-type->search-model entity-types)))
                          metabot-search-models)
        _               (log/infof "[METABOT-SEARCH] Converted entity-types %s to search-models %s" entity-types search-models)
        metabot         (t2/select-one :model/Metabot :entity_id (get-in metabot.config/metabot-config [metabot-id :entity-id] metabot-id))
        use-verified?   (if metabot-id
                          (:use_verified_content metabot)
                          false)
        embedded-metabot?  (= metabot-id metabot.config/embedded-metabot-id)
        ;; Caller-supplied `collection-id` wins; otherwise fall back to the metabot's
        ;; configured collection for embedded/NLQ profiles.
        collection-id   (or collection-id
                            (when (or embedded-metabot? (= profile-id "nlq"))
                              (:collection_id metabot)))
        ;; Always merge the metabot curator-boost overrides; explicit `:weights` from
        ;; the caller wins on a per-key basis so callers can still tune.
        weights         (merge metabot-weight-overrides weights)
        limit           (or limit 50)
        ;; Pick the semantic engine when active; it handles the hybrid blend internally.
        ;; Otherwise pass nil to use the default engine precedence (which lands on appdb).
        search-engine   (u/seek #{:search.engine/semantic} (search.engine/active-engines))
        search-context  (search/search-context
                         (cond-> {:search-string                       query
                                  :models                              search-models
                                  :table-db-id                         database-id
                                  :created-at                          created-at
                                  :last-edited-at                      last-edited-at
                                  :current-user-id                     api/*current-user-id*
                                  :is-impersonated-user?               (perms/impersonated-user?)
                                  :is-sandboxed-user?                  (perms/sandboxed-user?)
                                  :is-superuser?                       api/*is-superuser?*
                                  :current-user-perms                  @api/*current-user-permissions-set*
                                  :filter-items-in-personal-collection "exclude-others"
                                  :context                             :metabot
                                  :archived                            false
                                  :limit                               limit
                                  :offset                              0}
                           ;; Don't include search-native-query key if nil so that we don't
                           ;; inadvertently filter out search models that don't support it
                           search-native-query (assoc :search-native-query (boolean search-native-query))
                           use-verified?       (assoc :verified true)
                           weights             (assoc :weights weights)
                           search-engine       (assoc :search-engine (name search-engine))
                           collection-id       (assoc :collection collection-id)))
        results         (:data (search/search search-context))]
    (log/infof "[METABOT-SEARCH] Query '%s' returned entity types: %s"
               query (frequencies (map :model results)))
    (->> results
         (take limit)
         (map postprocess-search-result)
         enrich-with-collection-descriptions
         enrich-with-collection-paths
         enrich-with-database-engines
         remove-unreadable-transforms)))

(defn- format-search-output
  "Format search results as an LLM-ready string. One XML element per result so the agent
   can clearly see the type, attributes, and curation tags for each hit. The agent picks
   URIs and feeds them to read_resource for details."
  [query results]
  (let [results-xml (str/join "\n" (map llm-rep/search-result->xml results))]
    (te/lines
     (str "<results query=\"" (when query (llm-rep/escape-xml query))
          "\" total=\"" (count results) "\">")
     results-xml
     "</results>"
     "<instructions>"
     instructions/search-result-instructions "</instructions>")))

(defn- invalid-entity-types
  [entity-types allowed]
  (when (seq entity-types)
    (seq (remove allowed entity-types))))

(def ^:private default-search-limit 25)
(def ^:private max-search-limit 50)

(defn- do-search
  [label allowed-types search-opts {:keys [query entity_types limit
                                           database_id collection_id]
                                    :as _args}]
  (if-let [invalid (invalid-entity-types entity_types allowed-types)]
    {:output (str "Invalid entity_types for " label ": " (pr-str (vec invalid))
                  ". Allowed types: " (str/join ", " allowed-types) ".")}
    (try
      (let [results (search (merge {:query        query
                                    :entity-types (or (seq entity_types) (vec allowed-types))
                                    :metabot-id   shared/*metabot-id*
                                    :limit        (min max-search-limit
                                                       (or limit default-search-limit))}
                                   search-opts
                                   ;; Caller-supplied scope args from the LLM. `database_id`
                                   ;; may also be set via `search-opts` (sql-search), in which
                                   ;; case the explicit map entry from this caller wins.
                                   (cond-> {}
                                     database_id   (assoc :database-id database_id)
                                     collection_id (assoc :collection-id collection_id))))]
        {:output (format-search-output query results)
         :structured-output {:result-type :search
                             :data results
                             :total_count (count results)}})
      (catch Exception e
        (log/error e (str "Error in " label))
        {:output (str "Search failed: " (or (ex-message e) "Unknown error"))}))))

(def ^:private search-schema
  [:map {:closed true}
   [:query :string]
   [:entity_types {:optional true}
    [:maybe [:sequential [:enum "table" "model" "metric" "dashboard" "question" "collection"]]]]
   [:database_id   {:optional true} [:maybe :int]]
   [:collection_id {:optional true} [:maybe :int]]
   [:limit {:optional true} [:maybe [:int {:min 1 :max max-search-limit}]]]])

(mu/defn ^{:tool-name "search"
           :scope     scope/agent-search}
  search-tool
  "Search for tables, models, metrics, dashboards, saved questions, and collections."
  [args :- search-schema]
  (do-search "search"
             (sorted-set "collection" "dashboard" "metric" "model" "question" "table")
             {} args))

(def ^:private sql-search-schema
  [:map {:closed true}
   [:query :string]
   [:database_id :int]
   [:entity_types {:optional true}
    [:maybe [:sequential [:enum "table" "model"]]]]
   [:limit {:optional true} [:maybe [:int {:min 1 :max max-search-limit}]]]])

(mu/defn ^{:tool-name "search"
           :prompt    "sql_search.md"
           :scope     scope/agent-search}
  sql-search-tool
  "Search for SQL-queryable data sources (tables and models) within a database."
  [{:keys [database_id] :as args} :- sql-search-schema]
  (do-search "SQL search" (sorted-set "model" "table") {:database-id database_id} args))

(def ^:private nlq-search-schema
  [:map {:closed true}
   [:query :string]
   [:entity_types {:optional true}
    [:maybe [:sequential [:enum "table" "model" "metric" "question" "collection"]]]]
   [:database_id   {:optional true} [:maybe :int]]
   [:collection_id {:optional true} [:maybe :int]]
   [:limit {:optional true} [:maybe [:int {:min 1 :max max-search-limit}]]]])

(mu/defn ^{:tool-name "search"
           :prompt    "nlq_search.md"
           :scope     scope/agent-search}
  nlq-search-tool
  "Search for NLQ-queryable data sources (tables, models, metrics, questions, and collections)."
  [args :- nlq-search-schema]
  (do-search "NLQ search"
             (sorted-set "collection" "metric" "model" "question" "table")
             {:profile-id "nlq"} args))

(def ^:private transform-search-schema
  [:map {:closed true}
   [:query :string]
   [:search_native_query {:optional true} [:maybe :boolean]]
   [:entity_types {:optional true}
    [:maybe [:sequential [:enum "table" "model" "transform"]]]]
   [:limit {:optional true} [:maybe [:int {:min 1 :max max-search-limit}]]]])

(mu/defn ^{:tool-name "search"
           :prompt    "transform_search"
           :scope     scope/agent-search}
  transform-search-tool
  "Search for transforms, tables, and models."
  [{:keys [search_native_query] :as args} :- transform-search-schema]
  (do-search "transform search" (sorted-set "model" "table" "transform")
             {:search-native-query search_native_query} args))
