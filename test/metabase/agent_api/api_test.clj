(ns metabase.agent-api.api-test
  "Agent API functional tests using session-based authentication.
   JWT and scope-related tests live in metabase-enterprise.agent-api.api-test."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [environ.core :as env]
   [java-time.api :as t]
   [metabase.agent-api.api :as agent-api.api]
   [metabase.agent-api.settings :as agent-api.settings]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.normalize :as lib.normalize]
   [metabase.search.ingestion :as search.ingestion]
   [metabase.search.test-util :as search.tu]
   [metabase.session.models.session :as session.models]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.test.http-client :as client]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(use-fixtures :once (fixtures/initialize :db :web-server :test-users))

(defn- orders-count-query
  "Create a simple count query on the orders table using lib functions."
  []
  (-> (lib/query (mt/metadata-provider)
                 (lib.metadata/table (mt/metadata-provider) (mt/id :orders)))
      (lib/aggregate (lib/count))))

;;; ------------------------------------------------- Session Auth Tests ------------------------------------------------

(deftest agent-api-session-token-auth-test
  (testing "Session tokens via X-Metabase-Session header authenticate successfully"
    (let [session-key (session.models/generate-session-key)
          _           (t2/insert! :model/Session
                                  {:id          (session.models/generate-session-id)
                                   :user_id     (mt/user->id :rasta)
                                   :session_key session-key})
          response    (client/client :get 200 "agent/v1/ping"
                                     {:request-options {:headers {"x-metabase-session" session-key}}})]
      (is (= {:message "pong"} response))))

  (testing "Invalid session token returns 401"
    (let [fake-session-key (str (random-uuid))
          response         (client/client :get 401 "agent/v1/ping"
                                          {:request-options {:headers {"x-metabase-session" fake-session-key}}})]
      ;; Invalid session means standard middleware doesn't set metabase-user-id,
      ;; so our middleware sees no auth and returns missing_authorization
      (is (= {:error   "missing_authorization"
              :message "Authentication required. Use X-Metabase-Session header or Authorization: Bearer <jwt>."}
             response)))))

(deftest agent-api-expired-session-test
  (testing "Expired sessions are rejected by the standard session middleware"
    ;; Set max-session-age to 1 minute for this test
    (with-redefs [env/env (assoc env/env :max-session-age "1")]
      (let [session-key (session.models/generate-session-key)
            old-time    (t/minus (t/instant) (t/minutes 2))]
        (mt/with-temp [:model/Session _ {:user_id     (mt/user->id :rasta)
                                         :session_key session-key
                                         :created_at  old-time}]
          (testing "Session older than max-session-age is rejected"
            (is (= {:error   "missing_authorization"
                    :message "Authentication required. Use X-Metabase-Session header or Authorization: Bearer <jwt>."}
                   (client/client :get 401 "agent/v1/ping"
                                  {:request-options {:headers {"x-metabase-session" session-key}}})))))))))

(deftest agent-api-enabled-setting-test
  (testing "External Agent API routes return 403 when disabled"
    (mt/with-temporary-setting-values [agent-api.settings/agent-api-enabled? false]
      (is (= "Agent API is not enabled."
             (mt/user-http-request :rasta :get 403 "agent/v1/ping"))))))

(deftest ai-features-enabled-setting-test
  (testing "External Agent API routes return 403 when AI features are globally disabled"
    (mt/with-temporary-raw-setting-values [:ai-features-enabled? "false"
                                           :agent-api-enabled?   "true"]
      (is (= "AI features are not enabled."
             (mt/user-http-request :rasta :get 403 "agent/v1/ping"))))))

;;; ------------------------------------------------- Functional Tests --------------------------------------------------

(defn- visible-field-id
  "Find the real field ID for a field by display name within a table's visible columns."
  [table-id field-display-name]
  (let [mp           (mt/metadata-provider)
        query        (lib/query mp (lib.metadata/table mp table-id))
        visible-cols (lib/visible-columns query)]
    (->> visible-cols
         (filter #(= (lib/display-name query %) field-display-name))
         first
         :id)))

(deftest search-test
  (binding [search.ingestion/*force-sync* true]
    (search.tu/with-new-search-if-available-otherwise-legacy
      (mt/with-temp [:model/Table _ {:name "AgentSearchTestTable"}]
        (testing "Returns search results for term queries"
          (is (=? {:data        [{:type "table" :name "AgentSearchTestTable"}]
                   :total_count 1}
                  (mt/user-http-request :rasta :post 200 "agent/v1/search"
                                        {:term_queries ["AgentSearchTestTable"]}))))))))

(deftest coerce-query-list-test
  (let [coerce #'agent-api.api/coerce-query-list]
    (testing "arrays pass through unchanged"
      (is (= ["orders" "revenue"] (coerce ["orders" "revenue"]))))
    (testing "nil stays nil"
      (is (nil? (coerce nil))))
    (testing "a bare string becomes a single-element list"
      (is (= ["orders"] (coerce "orders"))))
    (testing "a JSON-stringified array of strings is unwrapped"
      (is (= ["orders" "revenue"] (coerce "[\"orders\", \"revenue\"]"))))
    (testing "JSON arrays with non-string elements are not unwrapped — they fall back to a literal single query so that downstream :sequential NonBlankString validation is never bypassed"
      (is (= ["[1, 2]"] (coerce "[1, 2]")))
      (is (= ["[\"\"]"] (coerce "[\"\"]"))))
    (testing "non-JSON strings become a single-element list"
      (is (= ["not json ["] (coerce "not json ["))))))

(defn- decode-query
  "Decode a base64-encoded query response to a Clojure map, then normalize it so lib functions work."
  [response]
  (-> response :query u/decode-base64 json/decode+kw lib.normalize/normalize))

(deftest construct-query-test
  (testing "Constructs a simple query from a table"
    (let [table-id (mt/id :orders)
          response (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                         {:source     {:type "table" :id table-id}
                                          :operations []
                                          :prompt     "show orders"})]
      (is (string? (:query response)) "Response should contain a query string")
      (is (= "show orders" (:prompt response)) "Response should echo the prompt")
      (let [decoded (decode-query response)]
        (is (= :mbql/query (lib/normalized-query-type decoded)))
        (is (= (mt/id) (lib/database-id decoded)))
        (is (= (mt/id :orders) (lib/primary-source-table-id decoded))))))

  (testing "Does not require prompt"
    (let [table-id (mt/id :orders)
          response (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                         {:source     {:type "table" :id table-id}
                                          :operations []})]
      (is (string? (:query response)) "Response should contain a query string")
      (is (not (contains? response :prompt)) "Response should only echo prompt when supplied")))

  (testing "Rejects oversized prompts before they can be persisted on MCP query handles"
    (mt/user-http-request :rasta :post 400 "agent/v2/construct-query"
                          {:source     {:type "table" :id (mt/id :orders)}
                           :operations []
                           :prompt     (apply str (repeat 10001 "x"))}))

  (testing "Respects explicit limit operation"
    (let [table-id (mt/id :orders)
          response (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                         {:source     {:type "table" :id table-id}
                                          :operations [["limit" 10]]
                                          :prompt     "show 10 orders"})
          decoded  (decode-query response)]
      (is (= 10 (lib/current-limit decoded)))))

  (testing "Returns 404 for non-existent table"
    (is (= "Not found."
           (mt/user-http-request :rasta :post 404 "agent/v2/construct-query"
                                 {:source     {:type "table" :id 999999}
                                  :operations []
                                  :prompt     "show orders"})))))

(deftest execute-query-test
  (testing "Executes a query and returns results with column metadata"
    (let [table-id       (mt/id :orders)
          construct-resp (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                               {:source     {:type "table" :id table-id}
                                                :operations [["limit" 5]]
                                                :prompt     "show 5 orders"})
          ;; Streaming response returns 202 (accepted) since it starts streaming before completion
          execute-resp   (mt/user-http-request :rasta :post 202 "agent/v1/execute"
                                               {:query (:query construct-resp)})]
      (is (=? {:status    "completed"
               :row_count 5
               :data      {:cols (fn [cols]
                                   (and (seq cols)
                                        (every? :name cols)
                                        (every? :base_type cols)))
                           :rows (fn [rows] (= 5 (count rows)))}}
              execute-resp))))

  (testing "Enforces agent query row limit even when query specifies a higher limit"
    (let [table-id       (mt/id :orders)
          construct-resp (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                               {:source     {:type "table" :id table-id}
                                                :operations [["limit" 300]]
                                                :prompt     "show all orders"})
          execute-resp   (mt/user-http-request :rasta :post 202 "agent/v1/execute"
                                               {:query (:query construct-resp)})]
      (is (=? {:status "completed" :row_count 200}
              execute-resp)))))

(deftest construct-metric-query-test
  (mt/with-temp [:model/Card metric {:name          "Test Metric"
                                     :type          :metric
                                     :database_id   (mt/id)
                                     :dataset_query (orders-count-query)}]
    (testing "Constructs a query from a metric"
      (let [response (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                           {:source     {:type "metric" :id (:id metric)}
                                            :operations []
                                            :prompt     "show metric"})]
        (is (string? (:query response)) "Response should contain a query string")
        (let [decoded (decode-query response)]
          (is (= :mbql/query (lib/normalized-query-type decoded)))
          (is (= (mt/id) (lib/database-id decoded))))))

    (testing "Returns 404 for non-existent metric"
      (is (= "Not found."
             (mt/user-http-request :rasta :post 404 "agent/v2/construct-query"
                                   {:source     {:type "metric" :id 999999}
                                    :operations []
                                    :prompt     "show metric"}))))))

(deftest construct-query-with-count-aggregation-test
  (testing "Count aggregation produces a valid query"
    (let [table-id (mt/id :orders)
          response (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                         {:source     {:type "table" :id table-id}
                                          :operations [["aggregate" ["count"]]
                                                       ["limit" 10]]
                                          :prompt     "count orders"})]
      (is (string? (:query response)))
      (let [decoded (decode-query response)]
        (is (= 1 (count (lib/aggregations decoded))))))))

(deftest construct-query-with-filters-test
  (testing "Constructs a query with filters"
    (let [table-id (mt/id :orders)
          field-id (mt/id :orders :id)
          response (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                         {:source     {:type "table" :id table-id}
                                          :operations [["filter" ["not-null" ["field" field-id]]]
                                                       ["limit" 10]]
                                          :prompt     "show filtered orders"})]
      (is (string? (:query response)))
      (let [decoded (decode-query response)]
        (is (seq (lib/filters decoded)) "Query should have filters")))))

(deftest combined-query-test
  (testing "Returns results for a table query that fits in a single page"
    (let [table-id (mt/id :orders)
          field-id (visible-field-id table-id "ID")
          response (mt/user-http-request :rasta :post 202 "agent/v2/query"
                                         {:source     {:type "table" :id table-id}
                                          :operations [["order-by" ["field" field-id]]
                                                       ["limit" 5]]})]
      (is (=? {:status             "completed"
               :row_count          5
               :continuation_token nil?
               :data               {:cols sequential?
                                    :rows (fn [rows] (= 5 (count rows)))}}
              response))))

  (testing "Continuation token returns next page of results when the total limit exceeds the page size"
    (let [table-id   (mt/id :orders)
          field-id   (visible-field-id table-id "ID")
          page-size  200
          total-rows 250
          page1      (mt/user-http-request :rasta :post 202 "agent/v2/query"
                                           {:source     {:type "table" :id table-id}
                                            :operations [["order-by" ["field" field-id]]
                                                         ["limit" total-rows]]})
          page2      (mt/user-http-request :rasta :post 202 "agent/v2/query"
                                           {:continuation_token (:continuation_token page1)})]
      (is (=? {:row_count          page-size
               :continuation_token string?
               :data               {:rows (fn [rows] (= page-size (count rows)))}}
              page1))
      (is (=? {:row_count          (- total-rows page-size)
               :continuation_token nil?
               :data               {:rows (fn [rows] (= (- total-rows page-size) (count rows)))}}
              page2))
      (is (not= (get-in page1 [:data :rows])
                (get-in page2 [:data :rows]))
          "Pages should return different rows")))

  (testing "No continuation_token when all rows are returned"
    (is (=? {:status             "completed"
             :continuation_token nil?}
            (mt/user-http-request :rasta :post 202 "agent/v2/query"
                                  {:source     {:type "table" :id (mt/id :orders)}
                                   :operations [["aggregate" ["count"]]]}))))

  (testing "Per-page cap limits a single page to 200 rows even when the total limit is higher"
    (is (=? {:status    "completed"
             :row_count (fn [n] (<= n 200))}
            (mt/user-http-request :rasta :post 202 "agent/v2/query"
                                  {:source     {:type "table" :id (mt/id :orders)}
                                   :operations [["limit" 1000]]})))))

(defn- make-continuation-token [pagination]
  (-> {:query {:database (mt/id) :stages [{:source-table (mt/id :orders)}]}
       :pagination pagination}
      json/encode
      u/encode-base64))

(deftest continuation-token-validation-test
  (testing "Malformed pagination ints in a continuation token produce a 400, not a 500.
            This is robustness — the token isn't a trust boundary, since a caller can
            always issue a fresh program."
    (doseq [[label pagination] [["zero limit"         {:limit 0      :page 1}]
                                ["negative limit"     {:limit -10    :page 1}]
                                ["non-integer limit"  {:limit "lots" :page 1}]
                                ["zero page"          {:limit 200    :page 0}]
                                ["negative page"      {:limit 200    :page -1}]
                                ["non-integer page"   {:limit 200    :page "next"}]]]
      (testing label
        (mt/user-http-request :rasta :post 400 "agent/v2/query"
                              {:continuation_token (make-continuation-token pagination)})))))

(deftest combined-query-metric-test
  (mt/with-temp [:model/Card metric {:name          "Test Metric"
                                     :type          :metric
                                     :database_id   (mt/id)
                                     :dataset_query (orders-count-query)}]
    (testing "Returns results for a metric query"
      (is (=? {:status    "completed"
               :row_count pos?}
              (mt/user-http-request :rasta :post 202 "agent/v2/query"
                                    {:source     {:type "metric" :id (:id metric)}
                                     :operations []}))))))

(deftest search-finds-metrics-test
  (binding [search.ingestion/*force-sync* true]
    (search.tu/with-new-search-if-available-otherwise-legacy
      (mt/with-temp [:model/Card _metric {:name          "AgentSearchTestMetric"
                                          :type          :metric
                                          :database_id   (mt/id)
                                          :dataset_query (orders-count-query)}]
        (testing "Returns metrics in search results"
          (is (=? {:data        [{:type "metric" :name "AgentSearchTestMetric"}]
                   :total_count 1}
                  (mt/user-http-request :rasta :post 200 "agent/v1/search"
                                        {:term_queries ["AgentSearchTestMetric"]}))))))))

;;; ------------------------------------------------ Create Question Tests -------------------------------------------

(deftest create-question-test
  (testing "Creates a saved question from a constructed query"
    (let [construct-resp (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                               {:source     {:type "table" :id (mt/id :orders)}
                                                :operations [["limit" 10]]})
          create-resp    (mt/user-http-request :rasta :post 200 "agent/v1/question"
                                               {:name  "Agent Test Question"
                                                :query (:query construct-resp)})]
      (is (=? {:id            pos?
               :name          "Agent Test Question"
               :display       "table"
               :collection_id nil
               :description   nil}
              create-resp))
      (is (t2/exists? :model/Card :id (:id create-resp)))
      (t2/delete! :model/Card :id (:id create-resp))))

  (testing "Creates a question with optional fields"
    (mt/with-temp [:model/Collection {coll-id :id} {:name "Agent Question Collection"}]
      (let [construct-resp (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                                 {:source     {:type "table" :id (mt/id :orders)}
                                                  :operations [["limit" 10]]})
            create-resp    (mt/user-http-request :rasta :post 200 "agent/v1/question"
                                                 {:name          "Agent Question With Options"
                                                  :query         (:query construct-resp)
                                                  :display       "bar"
                                                  :description   "A test question"
                                                  :collection_id coll-id})]
        (is (=? {:id            pos?
                 :name          "Agent Question With Options"
                 :display       "bar"
                 :collection_id coll-id
                 :description   "A test question"}
                create-resp))
        (t2/delete! :model/Card :id (:id create-resp))))))

;;; ----------------------------------------------- Create Dashboard Tests ------------------------------------------

(deftest create-dashboard-test
  (testing "Creates an empty dashboard"
    (let [resp (mt/user-http-request :rasta :post 200 "agent/v1/dashboard"
                                     {:name "Agent Test Dashboard"})]
      (is (=? {:id            pos?
               :name          "Agent Test Dashboard"
               :collection_id nil
               :description   nil
               :dashcard_ids  []}
              resp))
      (t2/delete! :model/Dashboard :id (:id resp))))

  (testing "Creates a dashboard with questions"
    (mt/with-temp [:model/Card {card1-id :id} {:name          "DashQ1"
                                               :dataset_query (orders-count-query)
                                               :display       :table}
                   :model/Card {card2-id :id} {:name          "DashQ2"
                                               :dataset_query (orders-count-query)
                                               :display       :bar}]
      (let [resp (mt/user-http-request :rasta :post 200 "agent/v1/dashboard"
                                       {:name         "Dashboard With Questions"
                                        :description  "Test dashboard"
                                        :question_ids [card1-id card2-id]})]
        (is (=? {:id           pos?
                 :name         "Dashboard With Questions"
                 :description  "Test dashboard"
                 :dashcard_ids #(= 2 (count %))}
                resp))
        ;; Verify dashcards reference the correct cards and have valid positions
        (let [dashcards (t2/select :model/DashboardCard :dashboard_id (:id resp))]
          (is (= #{card1-id card2-id} (set (map :card_id dashcards))))
          (is (every? #(and (nat-int? (:col %)) (nat-int? (:row %))
                            (pos? (:size_x %)) (pos? (:size_y %)))
                      dashcards)))
        (t2/delete! :model/Dashboard :id (:id resp)))))

  (testing "Creates a dashboard in a specific collection"
    (mt/with-temp [:model/Collection {coll-id :id} {:name "Agent Dashboard Collection"}]
      (let [resp (mt/user-http-request :rasta :post 200 "agent/v1/dashboard"
                                       {:name          "Collection Dashboard"
                                        :collection_id coll-id})]
        (is (= coll-id (:collection_id resp)))
        (t2/delete! :model/Dashboard :id (:id resp)))))

  (testing "Returns 404 when a question_id does not exist"
    (mt/user-http-request :rasta :post 404 "agent/v1/dashboard"
                          {:name         "Bad Dashboard"
                           :question_ids [999999]})))

(deftest create-collection-test
  (testing "Creates a root-level collection"
    (mt/with-current-user (mt/user->id :crowberto)
      (let [resp (mt/user-http-request :crowberto :post 200 "agent/v1/collection"
                                       {:name "Agent Root Coll"})]
        (is (=? {:id          pos?
                 :name        "Agent Root Coll"
                 :parent_id   nil
                 :location    "/"
                 :description nil}
                resp))
        (t2/delete! :model/Collection :id (:id resp)))))

  (testing "Creates a nested collection under a parent"
    (mt/with-temp [:model/Collection {parent-id :id} {:name "Agent Parent Coll"}]
      (let [resp (mt/user-http-request :crowberto :post 200 "agent/v1/collection"
                                       {:name                 "Agent Nested Coll"
                                        :description          "Nested under parent"
                                        :parent_collection_id parent-id})]
        (is (=? {:id          pos?
                 :name        "Agent Nested Coll"
                 :parent_id   parent-id
                 :description "Nested under parent"}
                resp))
        ;; location should encode the parent's id in the materialized path
        (is (= (str "/" parent-id "/") (:location resp)))
        (t2/delete! :model/Collection :id (:id resp)))))

  (testing "Returns 404 when parent_collection_id does not exist"
    (mt/user-http-request :crowberto :post 404 "agent/v1/collection"
                          {:name                 "Bad Parent Coll"
                           :parent_collection_id 999999}))

  (testing "Returns 403 when caller lacks write access on the parent"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp [:model/Collection {parent-id :id} {:name "Locked Parent"}]
        ;; Non-admin groups have no perms on the new collection by default.
        (mt/user-http-request :rasta :post 403 "agent/v1/collection"
                              {:name                 "Should Fail"
                               :parent_collection_id parent-id})))))

;;; ----------------------------------------------- Update Question Tests ------------------------------------------

(deftest update-question-test
  (testing "Patches simple fields (name, description, archived)"
    (mt/with-temp [:model/Card {card-id :id} {:name          "Agent Update Q Original"
                                              :dataset_query (orders-count-query)
                                              :display       :table}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/question/" card-id)
                                       {:name        "Renamed by Agent"
                                        :description "Set by agent"})]
        (is (=? {:id          card-id
                 :name        "Renamed by Agent"
                 :description "Set by agent"
                 :archived    false}
                resp)))
      ;; verify persisted
      (is (= "Renamed by Agent" (t2/select-one-fn :name :model/Card :id card-id)))
      (is (= "Set by agent" (t2/select-one-fn :description :model/Card :id card-id)))))

  (testing "Moving a card sets collection_id (subsumes move_card)"
    (mt/with-temp [:model/Collection {dest-coll-id :id} {:name "Agent Move Dest"}
                   :model/Card       {card-id :id}      {:name          "Card To Move"
                                                         :dataset_query (orders-count-query)
                                                         :display       :table}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/question/" card-id)
                                       {:collection_id dest-coll-id})]
        (is (= dest-coll-id (:collection_id resp))))
      (is (= dest-coll-id (t2/select-one-fn :collection_id :model/Card :id card-id)))))

  (testing "Archiving a card"
    (mt/with-temp [:model/Card {card-id :id} {:name          "Card To Archive"
                                              :dataset_query (orders-count-query)
                                              :display       :table}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/question/" card-id)
                                       {:archived true})]
        (is (true? (:archived resp))))
      (is (true? (t2/select-one-fn :archived :model/Card :id card-id)))))

  (testing "Replacing the underlying query via :query (base64)"
    (mt/with-temp [:model/Card {card-id :id} {:name          "Card To Re-query"
                                              :dataset_query (orders-count-query)
                                              :display       :table}]
      (let [products-id  (mt/id :products)
            new-query    (mt/user-http-request :rasta :post 200 "agent/v2/construct-query"
                                               {:source     {:type "table" :id products-id}
                                                :operations [["limit" 5]]})
            base64-query (:query new-query)
            _resp        (mt/user-http-request :rasta :put 200 (str "agent/v1/question/" card-id)
                                               {:query base64-query})
            persisted    (t2/select-one-fn :dataset_query :model/Card :id card-id)
            stages       (:stages persisted)
            source-table (some :source-table stages)]
        ;; Query was replaced - source-table changed from orders to products.
        (is (some? persisted))
        (is (= products-id source-table)
            (str "Expected persisted dataset_query :source-table to be the products table id "
                 products-id ", got " source-table)))))

  (testing "Returns 404 when card does not exist"
    (mt/user-http-request :rasta :put 404 "agent/v1/question/999999"
                          {:name "doesn't matter"}))

  (testing "Returns 403 when caller lacks write access on the card"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp [:model/Collection {locked-coll-id :id} {:name "Locked Coll"}
                     :model/Card       {card-id :id}        {:name          "Hidden Card"
                                                             :dataset_query (orders-count-query)
                                                             :display       :table
                                                             :collection_id locked-coll-id}]
        (mt/user-http-request :rasta :put 403 (str "agent/v1/question/" card-id)
                              {:name "Forbidden Rename"}))))

  (testing "Rejects unknown :display values with 400"
    (mt/with-temp [:model/Card {card-id :id} {:name          "Card Display Validation"
                                              :dataset_query (orders-count-query)
                                              :display       :table}]
      ;; The Malli enum on ::card-display should reject "potato" with a validation error.
      (mt/user-http-request :rasta :put 400 (str "agent/v1/question/" card-id)
                            {:display "potato"}))))

;;; ---------------------------------------------- Update Dashboard Tests ------------------------------------------

(deftest update-dashboard-test
  (testing "Patches name and description"
    (mt/with-temp [:model/Dashboard {dash-id :id} {:name "Original Name"}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                                       {:name "Renamed by Agent"
                                        :description "Set by agent"})]
        (is (=? {:id          dash-id
                 :name        "Renamed by Agent"
                 :description "Set by agent"
                 :archived    false}
                resp)))
      (is (= "Renamed by Agent" (t2/select-one-fn :name :model/Dashboard :id dash-id)))
      (is (= "Set by agent" (t2/select-one-fn :description :model/Dashboard :id dash-id)))))

  (testing "Moving a dashboard sets collection_id and moves its cards"
    (mt/with-temp [:model/Collection {dest-coll-id :id} {:name "Agent Dash Dest"}
                   :model/Dashboard  {dash-id :id}      {:name "Dash To Move"}
                   :model/Card       {card-id :id}      {:name          "Card On Dash"
                                                         :dataset_query (orders-count-query)
                                                         :display       :table
                                                         :dashboard_id  dash-id}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                                       {:collection_id dest-coll-id})]
        (is (= dest-coll-id (:collection_id resp))))
      (is (= dest-coll-id (t2/select-one-fn :collection_id :model/Dashboard :id dash-id)))
      ;; cards on the dashboard should follow
      (is (= dest-coll-id (t2/select-one-fn :collection_id :model/Card :id card-id)))))

  (testing "Archiving a dashboard cascades to its cards"
    (mt/with-temp [:model/Dashboard {dash-id :id} {:name "Dash To Archive"}
                   :model/Card      {card-id :id} {:name          "Cascading Card"
                                                   :dataset_query (orders-count-query)
                                                   :display       :table
                                                   :dashboard_id  dash-id}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                                       {:archived true})]
        (is (true? (:archived resp))))
      (is (true? (t2/select-one-fn :archived :model/Dashboard :id dash-id)))
      (is (true? (t2/select-one-fn :archived :model/Card :id card-id)))))

  (testing "Returns 404 when dashboard does not exist"
    (mt/user-http-request :rasta :put 404 "agent/v1/dashboard/999999"
                          {:name "doesn't matter"}))

  (testing "Returns 403 when caller lacks write access on the dashboard"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp [:model/Collection {locked-coll-id :id} {:name "Locked Coll For Dash"}
                     :model/Dashboard  {dash-id :id}        {:name          "Hidden Dash"
                                                             :collection_id locked-coll-id}]
        (mt/user-http-request :rasta :put 403 (str "agent/v1/dashboard/" dash-id)
                              {:name "Forbidden Rename"})))))

(deftest update-dashboard-dashcards-test
  (testing "Add a card to the dashboard (autoplaced)"
    (mt/with-temp [:model/Dashboard {dash-id :id} {:name "Phase B Add Target"}
                   :model/Card      {card-id :id} {:name          "Card to add"
                                                   :dataset_query (orders-count-query)
                                                   :display       :table}]
      (let [resp (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                                       {:dashcards [{:action "add" :card_id card-id}]})]
        (is (= 1 (count (:dashcard_ids resp))))
        (let [dashcards (t2/select :model/DashboardCard :dashboard_id dash-id)]
          (is (= 1 (count dashcards)))
          (is (= card-id (:card_id (first dashcards))))
          ;; Autoplaced - row and col are set even though we didn't provide them.
          (is (nat-int? (:row (first dashcards))))
          (is (nat-int? (:col (first dashcards))))))))

  (testing "Add multiple cards in one call - each one autoplaced w/o overlap"
    (mt/with-temp [:model/Dashboard {dash-id :id} {:name "Phase B Multi-add"}
                   :model/Card      {c1 :id}      {:name "C1" :dataset_query (orders-count-query) :display :table}
                   :model/Card      {c2 :id}      {:name "C2" :dataset_query (orders-count-query) :display :table}]
      (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                            {:dashcards [{:action "add" :card_id c1}
                                         {:action "add" :card_id c2}]})
      (let [dashcards (t2/select :model/DashboardCard :dashboard_id dash-id)
            positions (map (juxt :row :col) dashcards)]
        (is (= 2 (count dashcards)))
        (is (= 2 (count (set positions))) "Each dashcard should have a unique row/col"))))

  (testing "Remove a dashcard"
    (mt/with-temp [:model/Dashboard     {dash-id :id} {:name "Phase B Remove"}
                   :model/Card          {card-id :id} {:name "to remove" :dataset_query (orders-count-query) :display :table}
                   :model/DashboardCard {dashcard-id :id} {:dashboard_id dash-id :card_id card-id
                                                           :row 0 :col 0 :size_x 12 :size_y 9}]
      (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                            {:dashcards [{:action "remove" :dashcard_id dashcard-id}]})
      (is (zero? (count (t2/select :model/DashboardCard :dashboard_id dash-id))))))

  (testing "Move a dashcard to the top"
    (mt/with-temp [:model/Dashboard     {dash-id :id} {:name "Phase B Move"}
                   :model/Card          {card-id :id} {:name "movable" :dataset_query (orders-count-query) :display :table}
                   :model/DashboardCard {dashcard-id :id} {:dashboard_id dash-id :card_id card-id
                                                           :row 5 :col 3 :size_x 12 :size_y 9}]
      (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                            {:dashcards [{:action "move" :dashcard_id dashcard-id :position "top"}]})
      (let [moved (t2/select-one :model/DashboardCard :id dashcard-id)]
        (is (= 0 (:row moved)))
        (is (= 0 (:col moved))))))

  (testing "Mix add + remove + metadata patch in a single call"
    (mt/with-temp [:model/Dashboard     {dash-id :id} {:name "Phase B Mix"}
                   :model/Card          {keep-card :id} {:name "keep" :dataset_query (orders-count-query) :display :table}
                   :model/Card          {add-card :id}  {:name "add"  :dataset_query (orders-count-query) :display :table}
                   :model/Card          {drop-card :id} {:name "drop" :dataset_query (orders-count-query) :display :table}
                   :model/DashboardCard {keep-dc :id}   {:dashboard_id dash-id :card_id keep-card
                                                         :row 0 :col 0 :size_x 6 :size_y 4}
                   :model/DashboardCard {drop-dc :id}   {:dashboard_id dash-id :card_id drop-card
                                                         :row 4 :col 0 :size_x 6 :size_y 4}]
      (mt/user-http-request :rasta :put 200 (str "agent/v1/dashboard/" dash-id)
                            {:description "Mixed patch"
                             :dashcards [{:action "remove" :dashcard_id drop-dc}
                                         {:action "add"    :card_id add-card}]})
      (let [dashcards (t2/select :model/DashboardCard :dashboard_id dash-id)
            card-ids  (set (map :card_id dashcards))]
        (is (= #{keep-card add-card} card-ids))
        (is (some #(= keep-dc (:id %)) dashcards) "Untouched dashcard survives")
        (is (= "Mixed patch" (t2/select-one-fn :description :model/Dashboard :id dash-id))))))

  (testing "Returns 404 when add references a missing card"
    (mt/with-temp [:model/Dashboard {dash-id :id} {:name "Phase B Missing Card"}]
      (mt/user-http-request :rasta :put 404 (str "agent/v1/dashboard/" dash-id)
                            {:dashcards [{:action "add" :card_id 999999}]})))

  (testing "Returns 404 when remove references a dashcard not on this dashboard"
    (mt/with-temp [:model/Dashboard {dash-id :id} {:name "Phase B Wrong Dashcard"}]
      (mt/user-http-request :rasta :put 404 (str "agent/v1/dashboard/" dash-id)
                            {:dashcards [{:action "remove" :dashcard_id 999999}]})))

  (testing "Move requires :position - omitting it returns 400"
    (mt/with-temp [:model/Dashboard     {dash-id :id} {:name "Phase B Move Validation"}
                   :model/Card          {card-id :id} {:name "x" :dataset_query (orders-count-query) :display :table}
                   :model/DashboardCard {dc-id :id}   {:dashboard_id dash-id :card_id card-id
                                                       :row 0 :col 0 :size_x 6 :size_y 4}]
      (mt/user-http-request :rasta :put 400 (str "agent/v1/dashboard/" dash-id)
                            {:dashcards [{:action "move" :dashcard_id dc-id}]}))))

;;; ------------------------------------------------- Execute SQL Tests --------------------------------------------

(deftest execute-sql-test
  (testing "Admin can run a native SQL query (default perms)"
    (let [resp (mt/user-http-request :crowberto :post 202 "agent/v1/execute-sql"
                                     {:database_id (mt/id)
                                      :sql         "SELECT 1 AS one"})]
      (is (= "completed" (:status resp)))
      (is (= [[1]] (-> resp :data :rows)))))

  (testing "Returns 403 when the user lacks native-query permission"
    (mt/with-no-data-perms-for-all-users!
      (mt/user-http-request :rasta :post 403 "agent/v1/execute-sql"
                            {:database_id (mt/id)
                             :sql         "SELECT 1"})))

  (testing "Returns 403 when the kill-switch setting is disabled"
    (mt/with-temporary-setting-values [mcp-execute-sql-enabled false]
      (mt/user-http-request :crowberto :post 403 "agent/v1/execute-sql"
                            {:database_id (mt/id)
                             :sql         "SELECT 1"}))))

;;; ------------------------------------------------- Read Resource Tests -----------------------------------------

(deftest read-resource-test
  (testing "Dispatches a top-level URI through the shared resolver"
    (let [resp (mt/user-http-request :crowberto :post 200 "agent/v1/read-resource"
                                     {:uris ["metabase://databases"]})]
      (is (=? {:resources [(fn [r] (and (= "metabase://databases" (:uri r))
                                        (some? (:content r))))]
               :output    string?}
              resp))
      ;; Output is XML-shaped for LLM consumption.
      (is (str/includes? (:output resp) "<resources>"))
      (is (str/includes? (:output resp) "metabase://databases"))))

  (testing "Fetches a single-entity URI"
    (let [resp (mt/user-http-request :crowberto :post 200 "agent/v1/read-resource"
                                     {:uris [(str "metabase://table/" (mt/id :orders))]})]
      (is (= 1 (count (:resources resp))))
      (is (some? (-> resp :resources first :content)))))

  (testing "Returns 400 when too many URIs"
    (let [uris (vec (repeat 10 "metabase://databases"))]
      (mt/user-http-request :crowberto :post 400 "agent/v1/read-resource"
                            {:uris uris})))

  (testing "Reports a per-URI error rather than failing the whole call"
    (let [resp (mt/user-http-request :crowberto :post 200 "agent/v1/read-resource"
                                     {:uris ["metabase://nonsense/path"]})]
      (is (= 1 (count (:resources resp))))
      (is (nil? (-> resp :resources first :content)))
      (is (some? (-> resp :resources first :error))))))

