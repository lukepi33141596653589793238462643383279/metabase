(ns metabase.agent-api.api
  "Customer-facing Agent API for headless BI applications.
  Endpoints are versioned (e.g., /v1/search) and use standard HTTP semantics."
  (:require
   [clojure.string :as str]
   [malli.util :as mut]
   [metabase.agent-api.settings :as agent-api.settings]
   [metabase.agent-api.validation :as agent-api.validation]
   [metabase.agent-lib.core :as agent-lib]
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.api.macros.scope :as scope]
   [metabase.api.routes.common :as api.routes.common]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.collections.models.collection :as collection]
   [metabase.collections.models.collection.root :as collection.root]
   [metabase.dashboards.autoplace :as autoplace]
   [metabase.dashboards.models.dashboard :as dashboard]
   [metabase.events.core :as events]
   [metabase.lib.core :as lib]
   [metabase.metabot.config :as metabot.config]
   [metabase.metabot.core :as metabot]
   [metabase.metabot.feedback :as metabot.feedback]
   [metabase.metabot.tools.construct :as metabot-construct]
   [metabase.metabot.tools.resources :as metabot-resources]
   [metabase.metabot.tools.search :as metabot-search]
   [metabase.metabot.util :as metabot.u]
   [metabase.queries.core :as queries]
   [metabase.query-processor.core :as qp]
   [metabase.query-processor.middleware.permissions :as qp.perms]
   [metabase.query-processor.streaming :as qp.streaming]
   [metabase.request.core :as request]
   [metabase.server.streaming-response :as streaming-response]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [metabase.util.malli.registry :as mr]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

;;; --------------------------------------------------- Defaults ------------------------------------------------------

(def ^:private ^:const default-query-row-limit
  "Default row cap when :limit is omitted from a table query request."
  200)

(def ^:private ^:const page-size
  "Rows returned per page when paginating the combined query endpoint via continuation tokens.
   Also used as the query processor's per-call row constraint."
  200)

(def ^:private ^:const max-total-row-limit
  "Ceiling on the user-requested :limit for the combined query endpoint. Agents can paginate
   through up to this many rows across pages."
  2000)

;;; ---------------------------------------------------- Helpers ------------------------------------------------------

(defn submit-mcp-visualization-feedback!
  "Submit MCP Apps visualization feedback to Harbormaster.

  MCP Apps do not create `metabot_message` rows, so this intentionally skips
  local feedback persistence and forwards the MCP visualization context."
  [body]
  (let [metabot-id (api/check-500 (metabot.config/normalize-metabot-id metabot.config/embedded-metabot-id))
        body       (assoc body :metabot_id metabot-id)]
    (metabot.config/check-metabot-enabled!)
    (metabot.feedback/submit-to-harbormaster!
     (metabot.feedback/mcp-harbormaster-payload body))))

;;; --------------------------------------------------- Schemas ------------------------------------------------------

;; Response schemas for the Agent API.
;; - Use snake_case keys in schema definitions (JSON convention)
;; - Use :encode/api transformers to convert kebab-case data from internal functions
;; - Convert keyword enum values (like :table, :metric) to strings for JSON

(mr/def ::search-result-item
  "A table or metric returned from search."
  [:map {:encode/api #(update-keys % metabot.u/safe->snake_case_en)}
   [:id :int]
   [:type [:enum "table" "metric"]]
   [:name :string]
   [:display_name {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:database_id {:optional true} [:maybe :int]]
   [:database_schema {:optional true} [:maybe :string]]
   [:verified {:optional true} [:maybe :boolean]]
   [:updated_at {:optional true} [:maybe :any]]
   [:created_at {:optional true} [:maybe :any]]])

(mr/def ::search-response
  "Search results containing tables and metrics matching the query."
  [:map {:encode/api #(update-keys % metabot.u/safe->snake_case_en)}
   [:data [:sequential ::search-result-item]]
   [:total_count :int]])

;;; --------------------------------------------------- Endpoints ----------------------------------------------------

(api.macros/defendpoint :get "/v1/ping" :- [:map [:message :string]]
  "Health check endpoint for the Agent API."
  {:scope :unchecked}
  []
  {:message "pong"})

(defn- coerce-query-list
  "Defensive coercion for `/v1/search`'s query arguments. Some MCP clients (notably
   Codex) serialize array args through a string layer, so a caller that intended to
   send `[\"orders\"]` may actually send `\"[\\\"orders\\\"]\"`. Accept either shape:
   an array is returned as-is; a string that parses as a JSON array of non-blank
   strings is unwrapped; any other string is treated as a single-element query."
  [v]
  (cond
    (nil? v)        nil
    (sequential? v) v
    (string? v)     (or (try
                          (let [parsed (json/decode+kw v)]
                            (when (and (sequential? parsed)
                                       (every? #(and (string? %) (not (str/blank? %))) parsed))
                              parsed))
                          (catch Exception _ nil))
                        [v])
    :else           v))

(api.macros/defendpoint :post "/v1/search" :- ::search-response
  "Search for tables and metrics.

  Supports both term-based and semantic search queries. Results are ranked using
  Reciprocal Rank Fusion when both query types are provided."
  {:scope metabot/agent-search
   :tool  {:name "search"
           :title "Search Tables and Metrics"
           :description (str "Search for tables and metrics in Metabase. "
                             "Use term_queries for keyword search or semantic_queries for natural language search. "
                             "Both arguments are arrays of strings, for example term_queries: [\"orders\", \"revenue\"].")
           :annotations {:read-only? true}}}
  [_route-params
   _query-params
   {term-queries     :term_queries
    semantic-queries :semantic_queries}
   :- [:map
       [:term_queries {:optional true
                       :tool/description "Keyword search queries as an array of strings, for example [\"orders\", \"revenue\"]."}
        [:maybe [:or [:sequential ms/NonBlankString] ms/NonBlankString]]]
       [:semantic_queries {:optional true
                           :tool/description "Natural-language search queries as an array of strings, for example [\"how much revenue did we make\"]."}
        [:maybe [:or [:sequential ms/NonBlankString] ms/NonBlankString]]]]]
  (let [results (metabot-search/search
                 {:term-queries     (or (coerce-query-list term-queries) [])
                  :semantic-queries (or (coerce-query-list semantic-queries) [])
                  :entity-types     ["table" "metric"]
                  :limit            (or (request/limit) 50)})]
    {:data        results
     :total_count (count results)}))

;;; ------------------------------------------------ Construct Query -------------------------------------------------

(mr/def ::program-request
  "Request body for /v2/query.
  An agent-lib structured program with `:source` and `:operations`. The top-level
  `:source` must reference a database entity (`table`, `card`, `dataset`, or
  `metric`); `context` and nested `program` sources are rejected at the HTTP
  boundary by [[evaluate-program-for-execution]] because they require an
  in-process evaluation context."
  agent-lib/program-schema)

(def ^:private construct-query-prompt-max-length
  10000)

(def ^:private ConstructQueryPrompt
  (mut/update-properties
   [:and
    ms/NonBlankString
    [:string {:max construct-query-prompt-max-length}]]
   merge
   {:json-schema {:type        "string"
                  :minLength   1
                  :maxLength   construct-query-prompt-max-length
                  :description "The user's exact original message, when available. Pass it as-is without summarizing or rewriting."}}))

(mr/def ::construct-query-request
  "Request body for /v2/construct-query. Same as program-request, with an optional prompt
  capturing the user's original intent when a caller has one."
  (mut/merge agent-lib/program-schema
             [:map
              [:prompt {:optional true} [:maybe ConstructQueryPrompt]]]))

(mr/def ::construct-query-response
  "Response containing a base64-encoded MBQL query and, when supplied, the original prompt for use with /v1/execute."
  [:map
   [:query ms/NonBlankString]
   [:prompt {:optional true} ConstructQueryPrompt]])

(def ^:private allowed-program-source-types
  "Top-level program source types that the HTTP boundary accepts. `context` and
  nested `program` sources require an in-process evaluation context and are
  rejected here."
  #{"table" "card" "dataset" "metric"})

(def ^:private construct-query-tool-description
  "User-facing description for the `construct_query` MCP tool. Tuned to give the
  LLM enough structure to produce valid programs without reproducing the full
  reference — covers the program shape, canonical operator names, reference forms,
  and a few worked examples spanning the common patterns."
  (str
   "Construct a Metabase MBQL query from a structured program. The body structure is:\n"
   "`{\"source\": {...}, \"operations\": [...]}`\n"
   "For MCP calls, include `\"prompt\": \"<user's exact original message>\"` whenever you have the user's message; do not summarize or modify it.\n"
   "Returns `{\"query_handle\": \"<uuid>\"}` — pass `query_handle` to `execute_query` or `visualize_query`.\n"
   "For the full reference, read the `metabase://docs/construct-query.md` MCP resource.\n"
   "\n"
   "IMPORTANT: field IDs must come from entity-detail endpoints (`/v1/table/{id}`, `/v1/metric/{id}`). "
   "Do not invent IDs. The backend repairs minor mistakes (aliases, casing, over-wrapping) before validation, "
   "but the canonical names below always work.\n"
   "\n"
   "## Workflow\n"
   "1. Use `search_entities` / entity-detail tools to find the table/metric/model and its fields.\n"
   "2. Call `construct_query` with the program. Include the user's original `prompt` whenever available. You get back `{\"query_handle\": \"<uuid>\"}`.\n"
   "3. Pass `query_handle` to the follow-up `execute_query` or `visualize_query` call.\n"
   "Never embed IDs you did not read from a metadata endpoint — invented IDs will fail at execution.\n"
   "\n"
   "## Source\n"
   "One of `{\"type\": T, \"id\": N}`:\n"
   "- `table` — a database table\n"
   "- `card` — a saved question\n"
   "- `dataset` — a model (model card id)\n"
   "- `metric` — a metric (supplies its own aggregation and time dimension; extra aggregates usually unnecessary)\n"
   "\n"
   "## Top-level operations (applied in order)\n"
   "Each operation is `[\"op\", arg, ...]`:\n"
   "- `[\"filter\", clause]` — add a filter\n"
   "- `[\"aggregate\", agg-clause]` — add an aggregation\n"
   "- `[\"breakout\", ref-or-bucketed]` — add a grouping dimension\n"
   "- `[\"expression\", \"Name\", expr]` — define a named computed column (reference later with `expression-ref`)\n"
   "- `[\"with-fields\", [refs...]]` — restrict returned columns\n"
   "- `[\"order-by\", ref]` or `[\"order-by\", ref, \"asc\"|\"desc\"]` — sort\n"
   "- `[\"limit\", N]` — cap rows\n"
   "- `[\"join\", join-clause]` — join another entity\n"
   "- `[\"append-stage\"]` — start a new query stage (needed to filter on aggregated values)\n"
   "- `[\"with-page\", {\"page\": N, \"items\": M}]` — paginate\n"
   "\n"
   "## References (used as arguments inside operations)\n"
   "- `[\"field\", N]` — database field by id. Do NOT put options in a third slot (no `[\"field\", id, {...}]`); wrap instead\n"
   "- `[\"expression-ref\", \"Name\"]` — a named expression defined earlier\n"
   "- `[\"aggregation-ref\", N]` — the Nth `aggregate` defined earlier (0-based). REQUIRED when sorting by an aggregated value\n"
   "- `[\"measure\", N]` — a pre-defined measure on the source entity\n"
   "- `[\"with-temporal-bucket\", ref, unit]` — temporal bucketing. `unit` is one of: `minute` `hour` `day` `week` `month` `quarter` `year`. Also `day-of-week`, `hour-of-day`, etc. (extraction aliases)\n"
   "- `[\"with-binning\", ref, {\"strategy\": \"num-bins\"|\"bin-width\"|\"default\", ...}]` — numeric binning. E.g. `{\"strategy\": \"num-bins\", \"num-bins\": 10}`\n"
   "\n"
   "## Filter operators\n"
   "`=`, `!=`, `<`, `<=`, `>`, `>=`, `between`, `in`, `not-in`, `is-null`, `not-null`, `is-empty`, `not-empty`, "
   "`contains`, `does-not-contain`, `starts-with`, `ends-with`, `time-interval`, `and`, `or`, `not`, `segment`.\n"
   "Examples: `[\"=\", [\"field\", 101], \"active\"]`, `[\"between\", [\"field\", 305], \"2024-01-01\", \"2024-12-31\"]`, "
   "`[\"in\", [\"field\", 302], [10, 20, 30]]`, `[\"time-interval\", [\"field\", 305], -7, \"day\"]`.\n"
   "\n"
   "## Aggregation operators\n"
   "`count`, `sum`, `avg`, `min`, `max`, `distinct`, `median`, `stddev`, `var`, `percentile`, "
   "`count-where`, `sum-where`, `distinct-where`, `share`, `cum-count`, `cum-sum`. "
   "Examples: `[\"count\"]`, `[\"sum\", [\"field\", 302]]`, `[\"count-where\", [\"=\", [\"field\", 101], \"completed\"]]`.\n"
   "\n"
   "## Temporal helpers (for use in `expression` or as grouping)\n"
   "`get-year`, `get-quarter`, `get-month`, `get-week`, `get-day`, `get-day-of-week`, `get-hour`, `get-minute`, "
   "`datetime-add`, `datetime-diff`, `datetime-subtract`, `now`, `today`, `relative-datetime`, `absolute-datetime`, "
   "`with-temporal-bucket`, `convert-timezone`.\n"
   "\n"
   "## Examples\n"
   "Top 5 customers by revenue:\n"
   "```\n"
   "{\"source\": {\"type\": \"table\", \"id\": 42},\n"
   " \"operations\": [[\"aggregate\", [\"sum\", [\"field\", 302]]],\n"
   "                [\"breakout\", [\"field\", 101]],\n"
   "                [\"order-by\", [\"aggregation-ref\", 0], \"desc\"],\n"
   "                [\"limit\", 5]]}\n"
   "```\n"
   "Monthly revenue from a metric (metric supplies the aggregation):\n"
   "```\n"
   "{\"source\": {\"type\": \"metric\", \"id\": 10},\n"
   " \"operations\": [[\"breakout\", [\"with-temporal-bucket\", [\"field\", 305], \"month\"]],\n"
   "                [\"order-by\", [\"with-temporal-bucket\", [\"field\", 305], \"month\"], \"asc\"]]}\n"
   "```\n"
   "Filter on an aggregated value (requires `append-stage`):\n"
   "```\n"
   "{\"source\": {\"type\": \"table\", \"id\": 42},\n"
   " \"operations\": [[\"aggregate\", [\"sum\", [\"field\", 302]]],\n"
   "                [\"breakout\", [\"field\", 101]],\n"
   "                [\"append-stage\"],\n"
   "                [\"filter\", [\">\", [\"aggregation-ref\", 0], 1000]]]}\n"
   "```\n"
   "Named expression referenced later:\n"
   "```\n"
   "{\"source\": {\"type\": \"table\", \"id\": 42},\n"
   " \"operations\": [[\"expression\", \"Discount\", [\"-\", [\"field\", 302], [\"field\", 303]]],\n"
   "                [\"aggregate\", [\"sum\", [\"expression-ref\", \"Discount\"]]]]}\n"
   "```\n"
   "Previous-period comparison with `offset` (stay in the SAME stage — do NOT add `append-stage`):\n"
   "```\n"
   "{\"source\": {\"type\": \"table\", \"id\": 42},\n"
   " \"operations\": [[\"aggregate\", [\"sum\", [\"field\", 302]]],\n"
   "                [\"aggregate\", [\"offset\", [\"sum\", [\"field\", 302]], -1]],\n"
   "                [\"breakout\", [\"with-temporal-bucket\", [\"field\", 305], \"month\"]]]}\n"
   "```\n"
   "\n"
   "## Rules & common pitfalls\n"
   "Stage boundaries (most common source of errors):\n"
   "- Filtering on an aggregated value REQUIRES `append-stage` between the aggregate/breakout and the filter "
   "(see the \"filter on aggregated value\" example). Without it, `aggregation-ref` resolution fails in the same stage.\n"
   "- Defining an `expression` that uses `aggregation-ref` also REQUIRES `append-stage` first.\n"
   "- EXCEPTION: `offset` (previous-period comparison) stays in the same stage as its base aggregation and breakout — do NOT add `append-stage` for it.\n"
   "\n"
   "Refs & shapes:\n"
   "- Aggregation helpers take field refs, not bare IDs: `[\"sum\", [\"field\", 201]]`, never `[\"sum\", 201]`.\n"
   "- To sort by an aggregated value, use `[\"aggregation-ref\", N]` — not the original expression.\n"
   "- Do NOT put options in a third slot of `field` (no `[\"field\", id, {...}]`). Wrap instead: `[\"with-temporal-bucket\", [\"field\", id], \"month\"]` or `[\"with-binning\", [\"field\", id], {...}]`.\n"
   "- `case` takes `[[condition, value], ...]` branches and an optional bare fallback as the THIRD arg — do not wrap it as `{\"default\": ...}`. Omit the third arg when there is no fallback.\n"
   "- JSON objects appear only where a helper explicitly calls for one (e.g. `with-page`, `with-binning`). Everywhere else, use operator tuples.\n"
   "\n"
   "Joins & related tables:\n"
   "- If the source table's detail response already surfaces a related table's fields, use those field refs directly — no explicit join needed.\n"
   "- Reach for `join` + `with-join-conditions` only for custom aliases, self-joins, explicit joined-field selection, or when direct related-field refs are unavailable.\n"
   "- If an explicit join returns a permission error, the underlying table is not accessible — surface the error, do not retry with implicit refs.\n"
   "\n"
   "Metrics & dates:\n"
   "- A `metric` source already provides its own aggregation and time dimension. Add only the additional breakouts/filters you need.\n"
   "- When the user asks for an exact year (e.g. 2024), use `[\"=\", [\"field\", year_field], 2024]` or a `between` with explicit dates — not relative filters like `time-interval`.\n"))

(defn- evaluate-program-to-live-query
  "Resolve a program's source entity, evaluate the program via agent-lib, and return
  the live lib query (with lib metadata attached)."
  [program]
  (let [source-type (get-in program [:source :type])]
    (api/check (contains? allowed-program-source-types source-type)
               [400 (str "top-level program source must be one of: "
                         (str/join ", " (sort allowed-program-source-types)))]))
  (let [source-entity (metabot-construct/program-source->source-entity (:source program))
        result        (metabot-construct/execute-program source-entity nil program)]
    (get-in result [:structured-output :query])))

(defn- evaluate-program-for-execution
  "Evaluate a program and return a plain MBQL 5 query map suitable for serialization
  into a continuation token and execution by the QP."
  [program]
  (lib/prepare-for-serialization (evaluate-program-to-live-query program)))

(api.macros/defendpoint :post "/v2/construct-query" :- ::construct-query-response
  "Construct an MBQL query from a structured agent-lib program.

  The body is the program itself: a JSON object with `source` (identifying the
  table/card/dataset/metric to query), `operations` (an array of operator
  tuples), and `prompt` (the user's original request). Returns a base64-encoded
  MBQL query that can be executed via /v1/execute. See the agent_api reference
  for the full program syntax."
  {:scope metabot/agent-query-construct
   :tool  {:name "construct_query"
           :description construct-query-tool-description
           :annotations {:read-only? true :idempotent? true}}}
  [_route-params
   _query-params
   {:keys [prompt] :as request} :- ::construct-query-request]
  (let [program (dissoc request :prompt)
        query   (evaluate-program-for-execution program)]
    (cond-> {:query (-> query json/encode u/encode-base64)}
      prompt (assoc :prompt prompt))))

;;; ------------------------------------------------- Combined Query -------------------------------------------------

(defn- generate-continuation-token
  "Build a base64-encoded continuation token carrying the query and next-page pagination info.
   :limit is the user's total row cap across all pages, not the per-page size."
  [query-map total-limit page]
  (-> {:query      query-map
       :pagination {:limit total-limit :page (inc page)}}
      json/encode
      u/encode-base64))

(defn- decode-continuation-token
  "Decode a base64-encoded continuation token into {:query ... :pagination ...}.
   The token is client-supplied, so sanity-check the pagination ints to turn
   garbage into a 400 rather than a downstream 500. This is robustness, not a
   security boundary — a caller can always issue a fresh program to run any
   query they want."
  [token]
  (let [decoded (-> token u/decode-base64 json/decode+kw)
        {:keys [limit page]} (:pagination decoded)]
    (api/check (and (int? limit) (pos? limit))
               [400 "Invalid continuation token: limit must be a positive integer"])
    (api/check (and (int? page) (pos? page))
               [400 "Invalid continuation token: page must be a positive integer"])
    decoded))

(defn- total-row-limit
  "The user's requested :limit, defaulted when absent and capped at the combined
   endpoint's hard maximum. This is the app-level total-row budget enforced across
   paginated responses; each page's QP-level cap comes from `:page.items`, which
   `remaining-page-rows` clamps to respect this total."
  [live-query]
  (min (or (lib/current-limit live-query) default-query-row-limit)
       max-total-row-limit))

(defn- rows-before-page
  "Total rows consumed by the pages preceding `page`. Single source of truth for
   the page-size * (page - 1) arithmetic used by both sizing and pagination-exit."
  [page]
  (* (dec page) page-size))

(defn- remaining-page-rows
  "Rows to request for this page, respecting the user's total cap.
   Returns at most page-size, and never more than remaining rows under the cap."
  [total-limit page]
  (max 0 (min page-size (- total-limit (rows-before-page page)))))

(defn- more-pages-available?
  "True when this page was filled to its requested size *and* the total cap still
   has room for more rows — i.e. we should emit a continuation token."
  [page total-limit rows-returned items]
  (and (= rows-returned items)
       (< (rows-before-page (inc page)) total-limit)))

(defn- apply-page-to-query
  "Set `:page` on the last stage of a serialized MBQL 5 query map. Operates on the
  plain-map form because the continuation-token path only has that shape available —
  rehydrating to a live lib query here would require a metadata provider we don't
  currently plumb through the token."
  [query-map page items]
  (let [stages   (:stages query-map)
        last-idx (dec (count stages))]
    (assoc-in query-map [:stages last-idx :page] {:page page :items items})))

(defn- prepare-agent-query
  "Apply standard Agent API query preparation: middleware defaults and execution info."
  [query]
  (-> query
      (update-in [:middleware :js-int-to-string?] (fnil identity true))
      qp/userland-query-with-default-constraints
      (update :info merge {:executed-by api/*current-user-id*
                           :context     :agent})))

(defn- prepare-combined-query
  "Apply the tighter row cap used by the combined query endpoint. Each page is bounded
   by page-size; the user's total-limit is enforced separately via pagination."
  [query]
  (assoc (prepare-agent-query query)
         :constraints {:max-results           page-size
                       :max-results-bare-rows page-size}))

(mr/def ::query-request
  "Request body for /v2/query. Accepts either a structured program or a continuation_token."
  [:multi {:dispatch (fn [m]
                       (if (:continuation_token m) :continuation :program))}
   [:continuation [:map [:continuation_token ms/NonBlankString]]]
   [:program      ::program-request]])

(defn- initial-page-state
  "Normalize the two /v2/query entry points into a single {:query :total-limit :page}
   shape. A fresh program evaluates the user's program and computes a total-row budget
   from its `:limit`; a continuation token carries that state from a prior response."
  [body]
  (if-let [token (:continuation_token body)]
    (let [{:keys [query pagination]} (decode-continuation-token token)]
      {:query query :total-limit (:limit pagination) :page (:page pagination)})
    (let [live-query (evaluate-program-to-live-query body)]
      {:query       (lib/prepare-for-serialization live-query)
       :total-limit (total-row-limit live-query)
       :page        1})))

(api.macros/defendpoint :post "/v2/query"
  :- (streaming-response/streaming-response-schema ::query-response)
  "Execute a structured program and stream the results, with continuation-token pagination.

  Accepts either a program (same shape as /v2/construct-query) or a
  `continuation_token` from a previous response. Returns results with column
  metadata and an optional `continuation_token` for fetching the next page."
  {:scope "agent:query"
   :tool  {:name "query"
           :title "Query Tables and Metrics"
           :description (str "Execute a structured program and return results with column metadata. "
                             "If more rows are available the response includes a continuation_token "
                             "— pass it back to fetch the next page. Body is either a program (same "
                             "shape as construct_query) or {\"continuation_token\": \"...\"}. See the "
                             "`metabase://docs/construct-query.md` resource for program syntax.")
           :annotations {:read-only? true}}}
  [_route-params
   _query-params
   body :- ::query-request]
  (let [{:keys [query total-limit page]} (initial-page-state body)
        items           (remaining-page-rows total-limit page)
        mbql5-with-page (apply-page-to-query query page items)]
    (qp.streaming/streaming-response
     [rff :api]
      (qp/process-query
       (prepare-combined-query mbql5-with-page)
       (qp.streaming/transforming-query-response
        rff
        (fn [result]
          (assoc result :continuation_token
                 (when (more-pages-available? page total-limit (:row_count result) items)
                   (generate-continuation-token query total-limit page)))))))))

;;; ------------------------------------------------- Execute Query --------------------------------------------------

(mr/def ::execute-query-request
  "Request schema for /v1/execute. Accepts a base64-encoded MBQL query."
  [:map
   [:query {:tool/description "A base64-encoded query string returned by /v1/construct-query. Do not construct this value manually."}
    ms/NonBlankString]])

(mr/def ::column-metadata
  "Metadata for a single result column."
  [:map
   [:name           :string]
   [:base_type      :string]
   [:effective_type {:optional true} [:maybe :string]]
   [:display_name   :string]])

(mr/def ::execute-query-response
  "Response from query execution. The HTTP status is always 202 because results are streamed —
   check the `status` field to determine success or failure."
  [:map
   [:status       [:enum :completed :failed]]
   [:data         {:optional true}
    [:map
     [:cols [:sequential ::column-metadata]]
     [:rows [:sequential [:sequential :any]]]]]
   [:row_count    {:optional true} :int]
   [:running_time {:optional true} :int]
   [:error        {:optional true} :string]])

(mr/def ::query-response
  "Extends ::execute-query-response with an optional continuation_token for pagination."
  [:merge ::execute-query-response
   [:map [:continuation_token {:optional true} [:maybe :string]]]])

(api.macros/defendpoint :post "/v1/execute"
  :- (streaming-response/streaming-response-schema ::execute-query-response)
  "Execute an MBQL query and return results.

  Accepts a base64-encoded MBQL query (as returned by /v1/construct-query) and executes it,
  returning results with column metadata.

  Response format:
  - On success: {:data {:cols [...] :rows [...]} :row_count N :status :completed :running_time M}
  - On failure: {:status :failed :error \"message\" ...}

  Standard userspace query limits are enforced (2000 rows for simple queries, 10000 for aggregated)."
  {:scope metabot/agent-query-execute
   :tool  {:name "execute_query"
           :description (str "Execute a previously constructed query and return raw results with column metadata, "
                             "row count, and execution time. Use this when the user explicitly asks for raw data, "
                             "rows, columns, counts, metadata, or programmatic query results. If the user asks to "
                             "show, display, visualize, plot, chart, or present the result, use visualize_query "
                             "instead. "
                             "Pass `query_handle` (preferred) from construct_query rather than constructing the "
                             "base64 query yourself.")
           :annotations {:read-only? true :idempotent? true}}}
  [_route-params
   _query-params
   {encoded-query :query} :- ::execute-query-request]
  (let [query (-> encoded-query
                  u/decode-base64
                  json/decode+kw)]
    ;; The `mcp-execute-sql-enabled` setting is meant to let an admin turn off LLM-driven
    ;; raw SQL. `execute_sql` honors it directly; `/v1/execute` accepts an opaque base64
    ;; payload that could carry `:type :native`, so refuse those here too rather than only
    ;; gating the dedicated tool name.
    (when (and (= :native (:type query))
               (not (agent-api.settings/mcp-execute-sql-enabled)))
      (throw (ex-info "Native query execution is disabled on this instance"
                      {:status-code 403})))
    (qp.streaming/streaming-response [rff :api]
      (qp/process-query (prepare-combined-query query) rff))))

;;; --------------------------------------------------- Execute SQL --------------------------------------------------

(mr/def ::execute-sql-request
  "Request shape for /v1/execute-sql. The LLM passes a raw SQL string against a target database."
  [:map
   [:database_id ms/PositiveInt]
   [:sql         ms/NonBlankString]])

(api.macros/defendpoint :post "/v1/execute-sql"
  :- (streaming-response/streaming-response-schema ::execute-query-response)
  "Execute a raw SQL query against a database. Returns rows + column metadata.

  Requires the user to have native-query permission on the target database; the QP
  middleware enforces this. The instance-level `mcp-execute-sql-enabled` setting
  must also be on (it is by default)."
  {:scope metabot/agent-sql-execute
   :tool  {:name "execute_sql"
           :description (str "Execute a raw SQL query against a Metabase-connected database. "
                             "Use this ONLY when MBQL via construct_query cannot express the question. "
                             "User must have native-query permission on the target database. "
                             "Standard userspace query limits apply.")}}
  [_route-params
   _query-params
   {:keys [database_id sql]} :- ::execute-sql-request]
  ;; Kill-switch check: refuse with 403 when the admin has disabled execute_sql.
  (when-not (agent-api.settings/mcp-execute-sql-enabled)
    (throw (ex-info "execute_sql is disabled on this instance" {:status-code 403})))
  (let [query {:database database_id
               :type     :native
               :native   {:query sql}}]
    ;; Belt-and-suspenders: friendlier 403 from the tool layer than the QP's perms-exception.
    ;; The QP middleware (query-processor.middleware.permissions) will re-check inside process-query.
    (when-not (qp.perms/current-user-has-adhoc-native-query-perms? query)
      (throw (ex-info "You do not have permission to run native queries against this database."
                      {:status-code 403
                       :database_id database_id})))
    (qp.streaming/streaming-response [rff :api]
      (qp/process-query query rff))))

;;; -------------------------------------------------- Read Resource -------------------------------------------------

(mr/def ::read-resource-request
  "Request shape for /v1/read-resource. Accepts up to 5 metabase:// URIs."
  [:map
   [:uris [:sequential ms/NonBlankString]]])

(mr/def ::read-resource-item
  "One fetched resource. Either `:content` (success) or `:error` (failure) is present."
  [:map
   [:uri     ms/NonBlankString]
   [:content {:optional true} [:maybe :any]]
   [:error   {:optional true} [:maybe :string]]])

(mr/def ::read-resource-response
  "Response shape from /v1/read-resource. `:resources` is the per-URI result list;
  `:output` is the formatted XML string the LLM consumes."
  [:map
   [:resources [:sequential ::read-resource-item]]
   [:output    :string]])

(api.macros/defendpoint :post "/v1/read-resource" :- ::read-resource-response
  "Read one or more Metabase resources via metabase:// URI patterns.

  Dispatches into the shared URI resolver in `metabase.metabot.tools.resources`,
  which validates URIs, fetches entities with per-URI permission checks, and
  returns a map of `{:resources ... :output ...}`. Up to 5 URIs per call."
  {:scope metabot/agent-resource-read
   :tool  {:name "read_resource"
           :description (str "Read Metabase entities by metabase:// URI. "
                             "Examples: metabase://databases, metabase://database/{id}/tables, "
                             "metabase://collection/{id}/items, metabase://card/{id}, "
                             "metabase://dashboard/{id}/items, metabase://table/{id}/fields. "
                             "Up to 5 URIs per call. List endpoints cap at 25 items.")}}
  [_route-params
   _query-params
   body :- ::read-resource-request]
  (try
    (metabot-resources/read-resource body)
    (catch clojure.lang.ExceptionInfo e
      ;; The Metabot dispatcher's "too many URIs" guard throws ex-info without a
      ;; :status-code. Surface it as a 400 to the HTTP boundary rather than the
      ;; default 500.
      (throw (ex-info (ex-message e)
                      (merge {:status-code 400} (ex-data e))
                      e)))))

;;; ------------------------------------------------- Create Question ------------------------------------------------

(mr/def ::card-display
  "Display types accepted by Card. Validates LLM-passed values so a bogus
   value (e.g. `\"potato\"`) gets a 400 rather than persisting junk."
  [:enum "table" "bar" "line" "pie" "scatter" "area" "row" "combo" "pivot"
   "scalar" "smartscalar" "gauge" "progress" "funnel" "map" "waterfall" "sankey"])

(mr/def ::create-question-request
  [:map
   [:name                   ms/NonBlankString]
   [:query                  ms/NonBlankString]
   [:display                {:optional true} [:maybe ::card-display]]
   [:description            {:optional true} [:maybe :string]]
   [:collection_id          {:optional true} [:maybe ms/PositiveInt]]
   [:visualization_settings {:optional true} [:maybe :map]]])

(mr/def ::create-question-response
  [:map
   [:id            ms/PositiveInt]
   [:name          ms/NonBlankString]
   [:display       :string]
   [:collection_id [:maybe ms/PositiveInt]]
   [:description   [:maybe :string]]])

(api.macros/defendpoint :post "/v1/question" :- ::create-question-response
  "Save a previously constructed query as a named question (card).

  The `query` parameter accepts a `query_handle` (UUID) returned by `construct_query`,
  or a base64-encoded MBQL string. MCP callers should always use the handle.
  Optionally specify display type, description, collection, and visualization settings."
  {:scope metabot/agent-question-create
   :tool  {:name "create_question"
           :description (str "Save a query as a named question in Metabase. "
                             "Pass the `query_handle` returned by `construct_query`. "
                             "Optionally set display type (table, bar, line, pie, etc.), "
                             "description, and target collection.")}}
  [_route-params
   _query-params
   {:keys [query display description collection_id visualization_settings]
    question-name :name}
   :- ::create-question-request]
  (let [dataset-query (-> query u/decode-base64 json/decode+kw)
        card          (queries/create-card!
                       {:name                   question-name
                        :dataset_query          dataset-query
                        :display                (keyword (or display "table"))
                        :description            description
                        :collection_id          collection_id
                        :visualization_settings (or visualization_settings {})}
                       {:id api/*current-user-id*})]
    {:id            (:id card)
     :name          (:name card)
     :display       (name (:display card))
     :collection_id (:collection_id card)
     :description   (:description card)}))

;;; ------------------------------------------------- Update Question ------------------------------------------------

(mr/def ::update-question-request
  [:map
   [:name                   {:optional true} [:maybe ms/NonBlankString]]
   [:description            {:optional true} [:maybe :string]]
   [:collection_id          {:optional true} [:maybe ms/PositiveInt]]
   [:display                {:optional true} [:maybe ::card-display]]
   [:visualization_settings {:optional true} [:maybe :map]]
   [:archived               {:optional true} [:maybe :boolean]]
   [:query                  {:optional true} [:maybe ms/NonBlankString]]])

(mr/def ::update-question-response
  [:map
   [:id            ms/PositiveInt]
   [:name          ms/NonBlankString]
   [:display       :string]
   [:collection_id [:maybe ms/PositiveInt]]
   [:description   [:maybe :string]]
   [:archived      :boolean]])

(api.macros/defendpoint :put "/v1/question/:id" :- ::update-question-response
  "Update a saved question (card). Patch semantics - only fields that you pass are changed.

  Set `collection_id` to move the card to a different collection. Set `archived: true` to archive.
  Pass `query` (a query_handle from construct_query, or a base64 MBQL string) to replace the underlying query."
  {:scope metabot/agent-question-update
   :tool  {:name "update_question"
           :description (str "Update a saved question (card). Patch semantics - only fields you pass are changed. "
                             "To move a card to a different collection, set collection_id. "
                             "To archive, set archived true. To replace the underlying query, pass query "
                             "(a query_handle from construct_query).")}}
  [{:keys [id]} :- [:map [:id ms/PositiveInt]]
   _query-params
   body :- ::update-question-request]
  (let [card-before-update (api/write-check :model/Card id)
        card-updates       (cond-> {}
                             (contains? body :name)
                             (assoc :name (:name body))

                             (contains? body :description)
                             (assoc :description (:description body))

                             (contains? body :collection_id)
                             (assoc :collection_id (:collection_id body))

                             (contains? body :display)
                             (assoc :display (some-> (:display body) keyword))

                             (contains? body :visualization_settings)
                             (assoc :visualization_settings (:visualization_settings body))

                             (contains? body :archived)
                             (assoc :archived (boolean (:archived body)))

                             (contains? body :query)
                             (assoc :dataset_query
                                    (-> (:query body) u/decode-base64 json/decode+kw)))
        _                  (queries/update-card! {:card-before-update    card-before-update
                                                  :card-updates          card-updates
                                                  :actor                 @api/*current-user*
                                                  :delete-old-dashcards? false})
        updated            (t2/select-one :model/Card :id id)]
    {:id            (:id updated)
     :name          (:name updated)
     :display       (clojure.core/name (:display updated))
     :collection_id (:collection_id updated)
     :description   (:description updated)
     :archived      (boolean (:archived updated))}))

;;; ------------------------------------------------ Create Dashboard -----------------------------------------------

(mr/def ::create-dashboard-request
  [:map
   [:name          ms/NonBlankString]
   [:description   {:optional true} [:maybe :string]]
   [:collection_id {:optional true} [:maybe ms/PositiveInt]]
   [:question_ids  {:optional true} [:maybe [:sequential ms/PositiveInt]]]])

(mr/def ::create-dashboard-response
  [:map
   [:id            ms/PositiveInt]
   [:name          ms/NonBlankString]
   [:collection_id [:maybe ms/PositiveInt]]
   [:description   [:maybe :string]]
   [:dashcard_ids  [:sequential ms/PositiveInt]]])

(api.macros/defendpoint :post "/v1/dashboard" :- ::create-dashboard-response
  "Create a new dashboard, optionally populated with saved questions.

  Pass `question_ids` to add existing saved questions as cards on the dashboard.
  Cards are automatically positioned on the grid based on their display type."
  {:scope metabot/agent-dashboard-create
   :tool  {:name "create_dashboard"
           :description (str "Create a dashboard in Metabase. "
                             "Optionally pass question_ids to add saved questions as cards. "
                             "Cards are auto-positioned on the dashboard grid.")}}
  [_route-params
   _query-params
   {:keys [description collection_id question_ids]
    dashboard-name :name}
   :- ::create-dashboard-request]
  (api/create-check :model/Dashboard {:collection_id collection_id})
  (let [cards (when (seq question_ids)
                (mapv #(api/read-check :model/Card %) question_ids))
        dash  (t2/with-transaction [_conn]
                (let [dash (first (t2/insert-returning-instances!
                                   :model/Dashboard
                                   {:name          dashboard-name
                                    :description   description
                                    :parameters    []
                                    :creator_id    api/*current-user-id*
                                    :collection_id collection_id}))]
                  (when (seq cards)
                    (reduce (fn [placed card]
                              (let [display  (or (:display card) :table)
                                    position (autoplace/get-position-for-new-dashcard placed display)]
                                (t2/insert-returning-instance!
                                 :model/DashboardCard
                                 (merge position {:dashboard_id (:id dash)
                                                  :card_id      (:id card)}))
                                (conj placed position)))
                            []
                            cards))
                  dash))]
    (events/publish-event! :event/dashboard-create {:object dash :user-id api/*current-user-id*})
    {:id           (:id dash)
     :name         (:name dash)
     :collection_id (:collection_id dash)
     :description  (:description dash)
     :dashcard_ids (mapv :id (t2/select :model/DashboardCard :dashboard_id (:id dash)))}))

;;; ------------------------------------------------- Update Dashboard -----------------------------------------------

(mr/def ::dashcard-mutation
  "One dashcard mutation. Discriminated on `:action`:
   - `add`    : requires `card_id`. Auto-positioned. Optional `display_size`(\"wide\", \"tall\", or \"full\").
   - `remove` : requires `dashcard_id`.
   - `move`   : requires `dashcard_id` and `position` (\"top\" or \"bottom\")."
  [:multi {:dispatch :action}
   ["add"    [:map
              [:action       [:= "add"]]
              [:card_id      ms/PositiveInt]
              [:display_size {:optional true} [:maybe [:enum "wide" "tall" "full"]]]]]
   ["remove" [:map
              [:action      [:= "remove"]]
              [:dashcard_id ms/PositiveInt]]]
   ["move"   [:map
              [:action      [:= "move"]]
              [:dashcard_id ms/PositiveInt]
              [:position    [:enum "top" "bottom"]]]]])

(mr/def ::update-dashboard-request
  "Patch shape for `update_dashboard`. Metadata fields and an optional `dashcards` list of
   add/remove/move mutations applied in order."
  [:map
   [:name          {:optional true} [:maybe ms/NonBlankString]]
   [:description   {:optional true} [:maybe :string]]
   [:collection_id {:optional true} [:maybe ms/PositiveInt]]
   [:archived      {:optional true} [:maybe :boolean]]
   [:dashcards     {:optional true} [:maybe [:sequential ::dashcard-mutation]]]])

(mr/def ::update-dashboard-response
  [:map
   [:id            ms/PositiveInt]
   [:name          ms/NonBlankString]
   [:collection_id [:maybe ms/PositiveInt]]
   [:description   [:maybe :string]]
   [:archived      :boolean]
   [:dashcard_ids  [:sequential ms/PositiveInt]]])

(defn- size-override
  "Optional explicit size from the LLM. Returns {:width :height} or nil to fall back to defaults."
  [display-size]
  (case display-size
    "wide"    {:width 18 :height 6}
    "tall"    {:width 9  :height 12}
    "full"    {:width 24 :height 9}
    nil))

(defn- apply-dashcard-mutations!
  "Apply a sequence of LLM-friendly dashcard mutations. Returns {:added [...] :removed [...] :moved [...]}.

  Errors:
  - `card_id` that doesn't exist or the user can't read -> 404 / 403 via `api/read-check`.
  - `dashcard_id` that isn't on this dashboard -> 404 via `api/check-404`.

  Autoplace state walks the current dashcards list as we add, so each new card gets a unique slot."
  [dashboard-id mutations]
  (let [current (t2/select :model/DashboardCard :dashboard_id dashboard-id)
        state   (atom {:placed  (vec current)
                       :added   []
                       :removed []
                       :moved   []})]
    (doseq [{:keys [action card_id dashcard_id display_size position]} mutations]
      (case action
        "add"
        (let [card     (api/read-check :model/Card card_id)
              display  (or (:display card) :table)
              override (size-override display_size)
              position-fields (if override
                                (autoplace/get-position-for-new-dashcard
                                 (:placed @state) (:width override) (:height override)
                                 autoplace/default-grid-width)
                                (autoplace/get-position-for-new-dashcard
                                 (:placed @state) display))
              new-dashcard (first (t2/insert-returning-instances!
                                   :model/DashboardCard
                                   (merge position-fields
                                          {:dashboard_id dashboard-id
                                           :card_id      card_id})))]
          (swap! state #(-> %
                            (update :placed conj position-fields)
                            (update :added conj new-dashcard))))

        "remove"
        (let [existing (api/check-404
                        (t2/select-one :model/DashboardCard
                                       :id dashcard_id :dashboard_id dashboard-id))]
          (t2/delete! :model/DashboardCard :id dashcard_id)
          (swap! state #(-> %
                            (update :placed (fn [cards] (vec (remove (comp #{dashcard_id} :id) cards))))
                            (update :removed conj existing))))

        "move"
        (let [existing (api/check-404
                        (t2/select-one :model/DashboardCard
                                       :id dashcard_id :dashboard_id dashboard-id))
              ;; Strip the moved card from the placed list while we recompute its position,
              ;; otherwise autoplace will treat it as still occupying its old slot.
              other-placed (vec (remove (comp #{dashcard_id} :id) (:placed @state)))
              new-pos  (case position
                         "top"    {:row 0 :col 0
                                   :size_x (:size_x existing) :size_y (:size_y existing)}
                         "bottom" (autoplace/get-position-for-new-dashcard
                                   other-placed
                                   (:size_x existing) (:size_y existing)
                                   autoplace/default-grid-width))]
          (t2/update! :model/DashboardCard dashcard_id
                      (select-keys new-pos [:row :col]))
          (swap! state #(-> %
                            (assoc :placed (conj other-placed
                                                 (merge existing (select-keys new-pos [:row :col]))))
                            (update :moved conj (merge existing new-pos)))))))
    (select-keys @state [:added :removed :moved])))

(api.macros/defendpoint :put "/v1/dashboard/:id" :- ::update-dashboard-response
  "Update a dashboard. Patch semantics - only fields you pass are changed.

  Metadata: `name`, `description`, `collection_id`, `archived`. Dashcard mutations
  are submitted under `dashcards` as a list of `{action: add|remove|move, ...}`
  entries applied in order. `add` requires `card_id`; `remove` and `move` require
  `dashcard_id`."
  {:scope metabot/agent-dashboard-update
   :tool  {:name "update_dashboard"
           :description (str "Update a dashboard. Patch semantics - only fields you pass are changed. "
                             "Set collection_id to move it. Set archived true to archive. "
                             "Use dashcards to add, remove, or move cards: "
                             "[{\"action\":\"add\",\"card_id\":42},{\"action\":\"remove\",\"dashcard_id\":101}]. "
                             "Get dashcard_ids by reading metabase://dashboard/{id}/items via read_resource.")}}
  [{:keys [id]} :- [:map [:id ms/PositiveInt]]
   _query-params
   body :- ::update-dashboard-request]
  (let [current-dash (api/write-check :model/Dashboard id)
        updates      (cond-> {}
                       (contains? body :name)          (assoc :name (:name body))
                       (contains? body :description)   (assoc :description (:description body))
                       (contains? body :collection_id) (assoc :collection_id (:collection_id body))
                       (contains? body :archived)      (assoc :archived (boolean (:archived body))))
        mutations    (:dashcards body)
        result       (t2/with-transaction [_conn]
                       (when (seq updates)
                         (dashboard/cascade-card-state-from-dashboard-update! current-dash updates)
                         (t2/update! :model/Dashboard id updates)
                         ;; Fire :event/collection-touch with the *target* collection id so the
                         ;; activity feed records the right collection. Note: the REST endpoint
                         ;; at dashboards_rest/api.clj:1015 passes the dashboard id here instead,
                         ;; which appears to be a bug - the other collection-touch publishers
                         ;; (collections_rest, queries_rest, collections/models/collection) all
                         ;; pass the real collection id. Filed for follow-up.
                         (when (contains? updates :collection_id)
                           (events/publish-event! :event/collection-touch
                                                  {:collection-id (:collection_id updates)
                                                   :user-id       api/*current-user-id*})))
                       (when (seq mutations)
                         (apply-dashcard-mutations! id mutations)))]
    ;; Publish dashcard events outside the transaction, matching the REST endpoint's ordering.
    (when (seq (:added result))
      (events/publish-event! :event/dashboard-add-cards
                             {:object current-dash
                              :user-id api/*current-user-id*
                              :dashcards (:added result)}))
    (when (seq (:removed result))
      (events/publish-event! :event/dashboard-remove-cards
                             {:object current-dash
                              :user-id api/*current-user-id*
                              :dashcards (:removed result)}))
    (let [updated (t2/select-one :model/Dashboard :id id)]
      (events/publish-event! :event/dashboard-update
                             {:object updated :user-id api/*current-user-id*})
      {:id            (:id updated)
       :name          (:name updated)
       :collection_id (:collection_id updated)
       :description   (:description updated)
       :archived      (boolean (:archived updated))
       :dashcard_ids  (mapv :id (t2/select :model/DashboardCard :dashboard_id id))})))

;;; ------------------------------------------------ Create Collection -----------------------------------------------

(mr/def ::create-collection-request
  [:map
   [:name                 ms/NonBlankString]
   [:description          {:optional true} [:maybe :string]]
   [:parent_collection_id {:optional true} [:maybe ms/PositiveInt]]])

(mr/def ::create-collection-response
  [:map
   [:id            ms/PositiveInt]
   [:name          ms/NonBlankString]
   [:parent_id     [:maybe ms/PositiveInt]]
   [:location      ms/NonBlankString]
   [:description   [:maybe :string]]])

(api.macros/defendpoint :post "/v1/collection" :- ::create-collection-response
  "Create a new Collection.

  Pass `parent_collection_id` to nest under another collection; omit for a root-level collection.
  The caller must have write access to the parent (or root, if no parent given)."
  {:scope metabot/agent-collection-create
   :tool  {:name "create_collection"
           :description (str "Create a new collection in Metabase. "
                             "Set parent_collection_id to nest under another collection; "
                             "omit it for a root-level collection.")}}
  [_route-params
   _query-params
   {:keys [description parent_collection_id]
    collection-name :name}
   :- ::create-collection-request]
  (let [parent   (if parent_collection_id
                   (api/check-404 (t2/select-one :model/Collection :id parent_collection_id))
                   collection.root/root-collection)
        _        (api/write-check parent)
        location (collection/children-location parent)
        coll     (first (t2/insert-returning-instances!
                         :model/Collection
                         {:name        collection-name
                          :description description
                          :location    location}))]
    (events/publish-event! :event/collection-create
                           {:object coll :user-id api/*current-user-id*})
    {:id            (:id coll)
     :name          (:name coll)
     :parent_id     parent_collection_id
     :location      (:location coll)
     :description   (:description coll)}))

;;; ------------------------------------------------- Authentication -------------------------------------------------
;;
;; The Agent API supports two authentication modes:
;;
;; 1. **Session-based**: Client exchanges JWT at `/auth/sso` endpoint to get a session token,
;;    then passes it via `X-Metabase-Session` header. The standard Metabase session middleware
;;    handles validation and expiration checking automatically.
;;
;; 2. **Stateless JWT**: Client passes a JWT via `Authorization: Bearer <jwt>` header.
;;    The JWT is validated using the configured shared secret and max-age settings.
;;    Good for simple integrations and one-off API calls.

(defn- extract-bearer-token
  "Extract the token from a Bearer authorization header."
  [auth-header]
  (when (and auth-header (str/starts-with? (u/lower-case-en auth-header) "bearer "))
    (str/trim (subs auth-header 7))))

(defn- error-response
  "Create a 401 error response with structured JSON body."
  [error-type message]
  {:status  401
   :headers {"Content-Type" "application/json"}
   :body    {:error   error-type
             :message message}})

;;; -------------------------------------------- Stateless JWT Authentication --------------------------------------------

(defn- authenticate-with-jwt
  "Authenticate a request using a stateless JWT. Returns `{:user <user>}` on success, or
   `{:error <type> :message <msg>}` on failure. Does NOT create a session.

   Uses auth-identity/authenticate to validate the JWT, which reuses the same implementation as the /auth/sso endpoint
   and handles all settings validation.

   When the JWT contains a `\"scope\"` claim, the result includes `:scopes` — a parsed set of scope strings — so that
   [[enforce-authentication]] can attach it to the request for downstream scope enforcement."
  [token]
  (let [result (auth-identity/authenticate :provider/jwt {:token token})]
    (if (:success? result)
      ;; JWT is valid - look up user from the email extracted by the JWT provider
      ;; The provider uses jwt-attribute-email setting to extract the email from claims
      (if-let [user (when-let [email (get-in result [:user-data :email])]
                      (t2/select-one :model/User :%lower.email (u/lower-case-en email) :is_active true))]
        (let [scope-entry (-> result :jwt-data (find :scope))]
          (cond-> {:user user}
            scope-entry
            (assoc :scopes (or (scope/parse-scopes (val scope-entry)) #{}))))
        ;; Don't reveal whether the user exists or not - use same error as invalid JWT
        {:error   "invalid_jwt"
         :message "Invalid or expired JWT token."})
      ;; Authentication failed - map error to agent API format
      (case (:error result)
        :jwt-not-enabled {:error   "jwt_not_configured"
                          :message "JWT authentication is not configured. Set the JWT shared secret in admin settings."}
        ;; Default: use generic invalid JWT message (don't leak details)
        {:error   "invalid_jwt"
         :message "Invalid or expired JWT token."}))))

;;; -------------------------------------------------- Middleware ----------------------------------------------------

(defn- enforce-authentication
  "Middleware that ensures requests are authenticated.

   Ensures `:token-scopes` is present on authenticated requests.

   - For **session-authenticated** requests (where `:metabase-user-id` is already set by
     upstream middleware), preserves any pre-existing `:token-scopes` value if present,
     otherwise defaults to `#{::scope/unrestricted}` for unrestricted access.
   - For **JWT-authenticated** requests, derives `:token-scopes` from the JWT when a
     `\"scope\"` claim is present, falls back to any pre-existing `:token-scopes` on the
     request, and finally defaults to `#{::scope/unrestricted}` for unscoped JWTs.

   This ensures downstream scope enforcement never has to special-case nil within the
   agent API."
  [handler]
  (fn [{:keys [headers metabase-user-id token-scopes] :as request} respond raise]
    (cond
      ;; Already authenticated via X-Metabase-Session or synthetic request (e.g. MCP dispatch).
      ;; Preserve existing :token-scopes when present (MCP sets them on the synthetic request).
      metabase-user-id
      (handler (cond-> request
                 (not token-scopes) (assoc :token-scopes #{::scope/unrestricted}))
               respond raise)

      ;; Not authenticated via session - check for Bearer JWT
      :else
      (let [auth-header  (get headers "authorization")
            bearer-token (extract-bearer-token auth-header)]
        (cond
          ;; No authorization header and no session
          (nil? auth-header)
          (respond (error-response "missing_authorization"
                                   "Authentication required. Use X-Metabase-Session header or Authorization: Bearer <jwt>."))

          ;; Authorization header present but not Bearer format
          (nil? bearer-token)
          (respond (error-response "invalid_authorization_format"
                                   "Authorization header must use Bearer scheme: Authorization: Bearer <jwt>"))

          ;; Validate JWT
          :else
          (let [result (authenticate-with-jwt bearer-token)]
            (if-let [user (:user result)]
              (do
                (when (and (:scopes result) token-scopes (not= (:scopes result) token-scopes))
                  (log/warn "JWT scopes" (:scopes result)
                            "differ from pre-existing token-scopes" token-scopes))
                (request/with-current-user (:id user)
                  (handler (assoc request :token-scopes (or (:scopes result)
                                                            token-scopes
                                                            #{::scope/unrestricted}))
                           respond raise)))
              (respond (error-response (:error result) (:message result))))))))))

(def +auth
  "Agent API authentication middleware. Supports both session-based and stateless JWT authentication."
  (api.routes.common/wrap-middleware-for-open-api-spec-generation enforce-authentication))

(def +agent-api-enabled
  "Wrap routes so they may only be accessed when the Agent API is enabled."
  agent-api.validation/+agent-api-enabled)

;;; ---------------------------------------------------- Routes ------------------------------------------------------

(def ^{:arglists '([request respond raise])} routes
  "`/api/agent/` routes."
  (api.macros/ns-handler *ns* +auth))
