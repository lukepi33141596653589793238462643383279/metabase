# Metabase Agent API - Complete Reference

The Agent API is a REST API for building headless, agentic BI applications on
top of Metabase's semantic layer. It supports discovering tables and metrics,
inspecting their fields, constructing queries, and executing them - all scoped
to the authenticated user's permissions.

Base path: /api/agent

## Key concepts

- **Tables**: Database tables visible to the user.
- **Metrics**: Standalone saved queries that represent pre-defined aggregations
  (e.g., "Total Revenue"). Metrics are stored in collections and can be used
  as a data source in the API. They have a fixed aggregation, but can be
  filtered and grouped by their queryable dimensions. Read
  `metabase://metric/{id}/dimensions` via POST /v1/read-resource to inspect a
  metric's dimensions, and POST /v2/construct-query with a program whose
  `source` is `{"type": "metric", "id": <id>}` to query one.
- **Measures**: Lightweight, reusable aggregation expressions (e.g.,
  `SUM(total)`) associated with a specific table. Unlike metrics, measures are
  not standalone queries - they are building blocks that can be referenced in
  table queries via `["measure", id]` inside an `aggregate` operation. Discover
  available measures by reading `metabase://table/{id}/fields` via
  POST /v1/read-resource.
- **Segments**: Pre-defined filter conditions (e.g., "Active Users") that can
  be applied to queries via `["filter", ["segment", id]]`.
- **Field IDs**: Integer identifiers for database columns. These are the real
  database field IDs returned by the table/metric detail endpoints. Use them
  inside operator forms as `["field", N]`.

## Authentication

Two modes, both requiring JWT to be configured in Metabase admin settings
(Admin > Settings > Authentication > JWT):

### 1. Stateless JWT (recommended for agents)

Pass a signed JWT directly in each request:

```
Authorization: Bearer <jwt>
```

The JWT must be signed with the shared secret configured in Metabase. Required
claims:

| Claim | Type   | Required | Description                         |
| ----- | ------ | -------- | ----------------------------------- |
| iat   | int    | Yes      | Issued-at time (Unix seconds). JWT  |
|       |        |          | must be <180 seconds old.           |
| email | string | Yes      | Email matching a Metabase user. The |
|       |        |          | claim name is configurable via the  |
|       |        |          | jwt-attribute-email admin setting   |
|       |        |          | (default: "email").                 |

Optional claims: first_name, last_name, groups (for group sync).

Example JWT payload:

```json
{
  "iat": 1706640000,
  "email": "analyst@example.com"
}
```

### 2. Session-based

Exchange a JWT at `POST /auth/sso/to_session` to get a session token, then pass it via:

```
X-Metabase-Session: <session-token>
```

### Error responses (401)

```json
{"error": "missing_authorization", "message": "Authentication required. Use X-Metabase-Session header or Authorization: Bearer <jwt>."}
{"error": "invalid_authorization_format", "message": "Authorization header must use Bearer scheme: Authorization: Bearer <jwt>"}
{"error": "invalid_jwt", "message": "Invalid or expired JWT token."}
{"error": "jwt_not_configured", "message": "JWT authentication is not configured. Set the JWT shared secret in admin settings."}
```

## Permissions

All endpoints enforce the authenticated user's data permissions. If the user
lacks access to a table or metric, the API returns 403.

## Parameter naming conventions

Query parameters on GET endpoints use kebab-case (e.g., `with-fields`,
`with-related-tables`). JSON request and response bodies use snake_case (e.g.,
`table_id`, `field_id`). This applies consistently across all endpoints.

## Endpoints

### GET /v1/ping

Health check.

Response: `{"message": "pong"}`

### POST /v1/read-resource

Read one or more Metabase entities by `metabase://` URI. Replaces the older
per-entity GET endpoints (`/v1/table/{id}`, `/v1/metric/{id}`, the
`*_field_values` endpoints, and various GET browse endpoints) with a single
unified surface.

Request:

```json
{
  "uris": ["metabase://table/42", "metabase://metric/10/dimensions"]
}
```

Up to 5 URIs per call. List endpoints (e.g. `metabase://databases`,
`metabase://collection/{id}/items`) cap at 25 items and signal `truncated`
plus `total` when more are available; drill into specific URIs or refine
via `/v1/search`.

#### URI catalog

**Navigation (top-level lists):**

- `metabase://databases`
- `metabase://collections` (add `?tree=true` for the full hierarchy)
- `metabase://user/recent-items`

**Database drill-down:**

- `metabase://database/{id}`
- `metabase://database/{id}/tables`
- `metabase://database/{id}/models`
- `metabase://database/{id}/schemas`
- `metabase://database/{id}/schemas/{schemaName}/tables`

**Collection drill-down:**

- `metabase://collection/{id}` (`id` may be an integer or `"root"` / `"trash"`)
- `metabase://collection/{id}/items`
- `metabase://collection/{id}/subcollections`

**Entity drill-down:**

- `metabase://table/{id}` (`/fields`, `/fields/{field_id}`, `/derived`)
- `metabase://model/{id}` (`/fields`, `/fields/{field_id}`, `/sources`)
- `metabase://question/{id}` (`/fields`, `/fields/{field_id}`, `/sources`)
- `metabase://metric/{id}` (`/dimensions`, `/dimensions/{dimension_id}`)
- `metabase://transform/{id}` (`/sources`, `/target`)
- `metabase://dashboard/{id}` (`/items`)

Response:

```json
{
  "resources": [
    {"uri": "metabase://table/42", "content": { ... }},
    {"uri": "metabase://metric/10/dimensions", "content": { ... }}
  ],
  "output": "<resources>...</resources>"
}
```

Per-URI permission checks happen inside the dispatcher: single-entity URIs
return `:error` when `mi/can-read?` denies, list endpoints silently filter
unreadable items.

`:output` is an XML-shaped string formatted for LLM consumption. Programmatic
callers should rely on the `:resources` array.

Errors:

- 400 when more than 5 URIs are provided.
- The endpoint never raises 404 for a single-URI miss; per-URI errors are
  reported inside the response under `resources[].error`.

### POST /v1/search

Search for tables and metrics. Supports keyword and semantic search. Results
are ranked using Reciprocal Rank Fusion when both query types are provided.

Request:

```json
{
  "term_queries": ["revenue", "orders"],
  "semantic_queries": ["how much money did we make"]
}
```

At least one of `term_queries` or `semantic_queries` should be provided. Both
are arrays of strings. Results are limited to 50 by default.

Response:

```json
{
  "data": [
    {
      "type": "table",
      "id": 42,
      "name": "ORDERS",
      "display_name": "Orders",
      "description": "All customer orders",
      "database_id": 1,
      "database_schema": "PUBLIC",
      "verified": false
    },
    {
      "type": "metric",
      "id": 10,
      "name": "Total Revenue",
      "verified": true
    }
  ],
  "total_count": 2
}
```

### POST /v2/construct-query

Construct an MBQL query from a structured agent-lib program. Returns a
base64-encoded query string to pass to `/v1/execute`.

**Important**: All field IDs used in operations must come from
POST /v1/read-resource (e.g. `metabase://table/{id}/fields` or
`metabase://metric/{id}/dimensions`). Always fetch entity details first.

The request body **is** the program - there is no envelope. A program is a
JSON object with two keys:

- `source` - identifies the entity to query (`table`, `card`, `dataset`, or
  `metric` plus an `id`).
- `operations` - an ordered array of operator tuples to apply on top of the
  source. Each operation is itself an array: `["operator", arg1, arg2, ...]`.

The agent-lib backend automatically repairs small mistakes (operator aliases,
casing, scalar wrapping) before validation, so you don't need to be perfectly
precise about every detail - but the canonical operator names listed below
will always work.

#### Request format

```json
{
  "source": { "type": "table", "id": 42 },
  "operations": [
    ["filter", [">", ["field", 302], 100]],
    ["aggregate", ["sum", ["field", 302]]],
    ["breakout", ["with-temporal-bucket", ["field", 305], "month"]],
    ["order-by", ["aggregation-ref", 0], "desc"],
    ["limit", 100]
  ]
}
```

For a metric source (the metric supplies its own aggregation, so additional
aggregates are usually unnecessary):

```json
{
  "source": { "type": "metric", "id": 10 },
  "operations": [
    ["filter", [">", ["field", 305], "2024-01-01"]],
    ["breakout", ["with-temporal-bucket", ["field", 305], "month"]]
  ]
}
```

#### Response

```json
{ "query": "eyJkYXRhYmFzZSI6MSwi..." }
```

#### Source types

The top-level `source` must be one of these - `context` and nested `program`
sources are reserved for in-process callers and are rejected at the HTTP
boundary.

| Type      | Meaning                                                       |
| --------- | ------------------------------------------------------------- |
| `table`   | Query a database table directly (`id` is a table ID)          |
| `card`    | Query a saved question (`id` is a card ID)                    |
| `dataset` | Query a model (`id` is the model's card ID)                   |
| `metric`  | Query a metric, inheriting its aggregation and time dimension |

#### Top-level operations

Each operation is an array starting with the operator name. Operations are
applied in order to the query stage produced from `source`.

| Operator       | Shape                                           | Description                                             |
| -------------- | ----------------------------------------------- | ------------------------------------------------------- |
| `filter`       | `["filter", clause]`                            | Add a filter clause to the current stage                |
| `aggregate`    | `["aggregate", agg-clause]`                     | Add an aggregation                                      |
| `breakout`     | `["breakout", ref-or-bucketed]`                 | Add a grouping dimension                                |
| `expression`   | `["expression", "Name", expr]`                  | Define a named computed column                          |
| `with-fields`  | `["with-fields", [refs]]`                       | Restrict the returned columns                           |
| `order-by`     | `["order-by", ref]` or `["order-by", ref, dir]` | Sort by a field, expression-ref, or aggregation-ref     |
| `limit`        | `["limit", N]`                                  | Cap the number of returned rows                         |
| `join`         | `["join", join-clause]`                         | Join another table or card                              |
| `append-stage` | `["append-stage"]`                              | Start a new query stage (e.g. for post-aggregation ops) |
| `with-page`    | `["with-page", {"page": N, "items": M}]`        | Apply pagination on the current stage                   |

The full canonical list lives in
[src/metabase/agent_lib/capabilities/catalog/](../agent_lib/capabilities/catalog/).

#### References

References are nested operator forms used inside operations to point at fields,
expressions, or earlier aggregations.

| Form                             | Meaning                                                 |
| -------------------------------- | ------------------------------------------------------- |
| `["field", N]`                   | Reference a database field by ID                        |
| `["expression-ref", "Name"]`     | Reference a named expression defined earlier            |
| `["aggregation-ref", N]`         | Reference the Nth aggregation defined earlier (0-based) |
| `["measure", N]`                 | Reference a pre-defined measure on the source entity    |
| `["with-temporal-bucket", r, b]` | Bucket a temporal field by `b` (`day`, `month`, …)      |

#### Filter operators (used inside `["filter", …]`)

| Operator                       | Example                                                   |
| ------------------------------ | --------------------------------------------------------- |
| `=`, `!=`                      | `["=", ["field", 101], "active"]`                         |
| `<`, `<=`, `>`, `>=`           | `[">", ["field", 302], 100]`                              |
| `between`                      | `["between", ["field", 305], "2024-01-01", "2024-12-31"]` |
| `in`, `not-in`                 | `["in", ["field", 302], [10, 20, 30]]`                    |
| `is-null`, `not-null`          | `["is-null", ["field", 101]]`                             |
| `is-empty`, `not-empty`        | `["is-empty", ["field", 101]]`                            |
| `contains`, `does-not-contain` | `["contains", ["field", 303], "acme"]`                    |
| `starts-with`, `ends-with`     | `["starts-with", ["field", 303], "acme"]`                 |
| `time-interval`                | `["time-interval", ["field", 305], -7, "day"]`            |
| `and`, `or`, `not`             | `["and", filter1, filter2]`                               |
| `segment`                      | `["segment", 5]` - apply a pre-defined segment            |

#### Aggregation operators (used inside `["aggregate", …]`)

`count`, `sum`, `avg`, `min`, `max`, `distinct`, `median`, `stddev`, `var`,
`percentile`, `count-where`, `sum-where`, `distinct-where`, `share`,
`cum-count`, `cum-sum`.

```json
["aggregate", ["count"]]
["aggregate", ["sum", ["field", 302]]]
["aggregate", ["count-where", ["=", ["field", 101], "completed"]]]
```

#### Temporal helpers (commonly used in `expression` and `breakout`)

`get-year`, `get-quarter`, `get-month`, `get-week`, `get-day`,
`get-day-of-week`, `get-hour`, `get-minute`, `datetime-add`, `datetime-diff`,
`datetime-subtract`, `now`, `today`, `relative-datetime`, `absolute-datetime`,
`with-temporal-bucket`, `convert-timezone`.

#### Worked examples

**Top 5 customers by revenue**

```json
{
  "source": { "type": "table", "id": 42 },
  "operations": [
    ["aggregate", ["sum", ["field", 302]]],
    ["breakout", ["field", 101]],
    ["order-by", ["aggregation-ref", 0], "desc"],
    ["limit", 5]
  ]
}
```

**Conditional sum with a named expression**

```json
{
  "source": { "type": "table", "id": 42 },
  "operations": [
    ["expression", "Discount", ["-", ["field", 302], ["field", 303]]],
    ["aggregate", ["sum", ["expression-ref", "Discount"]]]
  ]
}
```

**Orders per month using a metric**

```json
{
  "source": { "type": "metric", "id": 10 },
  "operations": [
    ["breakout", ["with-temporal-bucket", ["field", 305], "month"]]
  ]
}
```

**Multi-stage: filter on an aggregated value**

```json
{
  "source": { "type": "table", "id": 42 },
  "operations": [
    ["aggregate", ["sum", ["field", 302]]],
    ["breakout", ["field", 101]],
    ["append-stage"],
    ["filter", [">", ["aggregation-ref", 0], 1000]]
  ]
}
```

#### Error responses

Validation, repair, and resolution errors are returned as `400 Bad Request`
with a structured JSON body:

```json
{
  "status-code": 400,
  "error": "invalid-generated-program",
  "path": "operations[2].field_id",
  "details": "operations[2].field_id: field 12345 is not accessible",
  "recovery": {
    "available": ["field 1001", "field 1002"],
    "suggestion": "Did you mean field 1001?"
  }
}
```

A non-existent table, card, or metric in `source` returns `404 Not Found`.

### POST /v2/query

Combined construct-and-execute endpoint with built-in pagination via
continuation tokens.

The body is either:

- A program (same shape as `/v2/construct-query`), **or**
- `{"continuation_token": "..."}` returned from a previous response.

Pagination is automatic. The per-page row limit is taken from your program's
`["limit", N]` operation if present, otherwise defaults to 200, and is hard-
capped at 200 rows for memory and LLM-context safety. If the page is full and
more rows may exist, the response includes a `continuation_token` you can
post back to fetch the next page.

```json
{
  "source": { "type": "table", "id": 42 },
  "operations": [
    ["order-by", ["field", 101]],
    ["limit", 50]
  ]
}
```

Response (HTTP 202, streaming):

```json
{
  "status": "completed",
  "data": {
    "cols": [{"name": "ID", "base_type": "type/Integer", "display_name": "ID"}],
    "rows": [[1], [2], [3], [...]]
  },
  "row_count": 50,
  "running_time": 87,
  "continuation_token": "eyJxdWVyeSI6ey..."
}
```

To fetch the next page:

```json
{ "continuation_token": "eyJxdWVyeSI6ey..." }
```

### POST /v1/execute

Execute a query returned by /v2/construct-query.

**Important: streaming response.** This endpoint streams results, so the HTTP
status code (202) is sent before query execution completes. A 202 status does
NOT guarantee the query succeeded - you must check the `status` field in the
response body. If the query fails mid-execution, the response body will contain
`"status": "failed"` with an error message, even though the HTTP status was 202.

Request:

```json
{ "query": "eyJkYXRhYmFzZSI6MSwi..." }
```

Response (HTTP 202):

The response body may contain additional fields beyond those documented here.
Ignore any fields not listed below - they are internal metadata and not part of
the stable API contract.

On success:

```json
{
  "status": "completed",
  "data": {
    "cols": [
      {
        "name": "CREATED_AT",
        "base_type": "type/DateTime",
        "effective_type": "type/DateTime",
        "display_name": "Created At"
      },
      {
        "name": "sum",
        "base_type": "type/Float",
        "effective_type": "type/Float",
        "display_name": "Sum of Total"
      }
    ],
    "rows": [
      ["2024-01-01T00:00:00Z", 15234.5],
      ["2024-02-01T00:00:00Z", 18102.75]
    ]
  },
  "row_count": 2,
  "running_time": 142
}
```

| Field        | Description                                                     |
| ------------ | --------------------------------------------------------------- |
| status       | `"completed"` on success, `"failed"` on error                   |
| data.cols    | Column metadata (name, base_type, effective_type, display_name) |
| data.rows    | Array of row arrays, in the same order as cols                  |
| row_count    | Number of rows returned                                         |
| running_time | Query execution time in milliseconds                            |

On failure:

```json
{ "status": "failed", "error": "Query error message" }
```

Row limits:

- Simple queries (no aggregation): 2000 rows max
- Aggregated queries: 10000 rows max

### POST /v1/execute-sql

Execute a raw SQL query against a database. Streams results in the same
format as `/v1/execute`. Use this only when MBQL via `/v2/construct-query`
cannot express the question.

Requires the caller to have native-query permission on the target database;
the QP middleware enforces this, with a friendly belt-and-suspenders check
at the tool layer. Returns 403 when the user lacks permission or when the
instance setting `mcp-execute-sql-enabled` is `false` (default `true`).

Request:

```json
{ "database_id": 1, "sql": "SELECT count(*) FROM orders" }
```

Response: identical shape to `/v1/execute`.

### POST /v1/question

Save a previously constructed query as a named question (card). Pass the
`query_handle` returned by `/v2/construct-query` as the `query` field
(the MCP layer transparently resolves handles for clients connected via
MCP; direct REST callers may instead pass the base64 query string).

Request:

```json
{
  "name": "Monthly Revenue",
  "query": "<query_handle or base64>",
  "display": "line",
  "description": "Revenue by month",
  "collection_id": 7,
  "visualization_settings": {}
}
```

Response:

```json
{
  "id": 42,
  "name": "Monthly Revenue",
  "display": "line",
  "collection_id": 7,
  "description": "Revenue by month"
}
```

### PUT /v1/question/{id}

Update a saved question (card). Patch semantics - only fields you pass are
changed. Subsumes "move card to collection" - setting `collection_id` moves
the card. Setting `archived: true` archives it. Passing `query` replaces the
underlying `dataset_query`.

Request:

```json
{
  "name": "Renamed Question",
  "description": "Updated description",
  "collection_id": 7,
  "display": "bar",
  "visualization_settings": {},
  "archived": false,
  "query": "<query_handle or base64>"
}
```

Response:

```json
{
  "id": 42,
  "name": "Renamed Question",
  "display": "bar",
  "collection_id": 7,
  "description": "Updated description",
  "archived": false
}
```

### POST /v1/dashboard

Create a new dashboard, optionally populated with saved questions.

Request:

```json
{
  "name": "Revenue Dashboard",
  "description": "...",
  "collection_id": 7,
  "question_ids": [42, 43]
}
```

When `question_ids` is provided, cards are auto-positioned on the grid based
on each card's display type.

Response:

```json
{
  "id": 7,
  "name": "Revenue Dashboard",
  "collection_id": 7,
  "description": "...",
  "dashcard_ids": [101, 102]
}
```

### PUT /v1/dashboard/{id}

Update a dashboard's metadata. Patch semantics. Setting `collection_id`
moves the dashboard (and its cards). Setting `archived: true` archives the
dashboard and cascades to its cards.

Request:

```json
{
  "name": "Renamed Dashboard",
  "description": "...",
  "collection_id": 7,
  "archived": false
}
```

Response:

```json
{
  "id": 7,
  "name": "Renamed Dashboard",
  "collection_id": 7,
  "description": "...",
  "archived": false
}
```

### POST /v1/collection

Create a new collection. Optionally nested under another collection via
`parent_collection_id`.

Request:

```json
{ "name": "Marketing", "description": "...", "parent_collection_id": 1 }
```

Response:

```json
{
  "id": 12,
  "name": "Marketing",
  "parent_id": 1,
  "location": "/1/",
  "description": "..."
}
```

## Typical workflow

1. **Search** - POST /v1/search to find relevant tables, metrics, cards, or dashboards
2. **Navigate** - POST /v1/read-resource with `metabase://` URIs to drill into
   databases, schemas, tables, fields, collections, dashboards, etc.
3. **Build query** - POST /v2/construct-query with a structured program
   (`source` + `operations`)
4. **Execute** - POST /v1/execute with the base64-encoded query, or use
   POST /v2/query to construct and execute in one round-trip with pagination
5. **Save (optional)** - POST /v1/question to persist the query as a saved
   question; PUT /v1/question/{id} to update one; POST /v1/dashboard to
   bundle questions into a dashboard
6. **Iterate** - Adjust the program and repeat

## Error handling

| HTTP Status | Meaning                                                     |
| ----------- | ----------------------------------------------------------- |
| 200         | Success (GET endpoints, construct-query)                    |
| 202         | Success (execute / query - streaming response)              |
| 400         | Invalid program (validation, repair, or resolution failure) |
| 401         | Authentication failure                                      |
| 403         | Insufficient permissions                                    |
| 404         | Entity not found                                            |

---
