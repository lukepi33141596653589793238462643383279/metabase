Hybrid keyword + semantic search across the Metabase instance. Returns one XML element per
hit, each carrying a `uri` attribute and curation flags. Search is **discovery**, not
summarization — pair it with `read_resource` to drill into specific URIs for details.

## When to use

- Open-ended discovery by topic ("revenue", "customer churn") when you don't know which
  container or specific item holds the answer.
- Use the navigation URIs on `read_resource` (`metabase://databases`, `metabase://collections?tree=true`,
  `metabase://collection/{id}/items`, `metabase://dashboard/{id}/items`) when you already
  know the structure to enumerate — don't search to "list everything".

## How to query

`query` is a single string. Phrase it the way a human would: a short noun phrase or
question fragment.

- ✓ `"customer retention"`, `"monthly recurring revenue"`, `"Q4 sales dashboard"`,
  `"orders by region"`
- ✗ `""` (empty), `"all dashboards"`, `"everything about customers"`

The backend runs your query through both keyword (full-text) and semantic (vector)
matching in parallel, then fuses the two — you don't need to send paraphrases.

**Iteration is cheap.** If your first query misses, run it again from a different angle:
- "customer churn" missed → try "retention rate" or "lost customers"
- Too many hits → narrow with `entity_types`, `database_id`, or `collection_id`
- Top hit isn't right → scan further down or refine the query

A short follow-up search beats one broad bundled query. Don't pre-hedge with multiple
phrasings in a single call — that's the backend's job.

## Filtering & scoping

`entity_types` — restrict to specific kinds. Allowed: `table`, `model`, `metric`,
`dashboard`, `question`, `collection`. Leave empty to search across all of them.

`database_id` — restrict to one database. Use when the user mentioned a database, or
after a broad search returned items spanning many databases and you want to narrow.

`collection_id` — restrict to a collection AND all its descendants. Use after listing
collections via `metabase://collections?tree=true`, or after a `collection`-typed search
hit, to drill into one part of the instance.

`limit` — default 25, max 50. Bump up for broad scans where you want to see the
landscape; keep low for narrow lookups with clear intent.

## Reading the output

```xml
<results query="orders" total="14">
<model id="1" name="Orders + People" uri="metabase://model/1" is_verified="true" is_official="true" database_id="1">
Sample orders joined with products
Collection: Examples
</model>
<table id="2" name="ORDERS" uri="metabase://table/2" database_id="1" fully_qualified_name="PUBLIC.ORDERS">
Confirmed Sample Company orders
</table>
<dashboard id="12" name="Sales Overview" uri="metabase://dashboard/12" is_container="true" is_verified="true">
Collection: Marketing/Q4
</dashboard>
<collection id="5" name="Sales Reports" uri="metabase://collection/5" is_container="true" is_official="true"/>
<question id="3" name="Customer satisfaction per category" uri="metabase://question/3" is_official="true" database_id="1">
Collection: Examples
</question>
...
</results>
```

Each result is an XML element. Curation attributes:
- `is_verified="true"` — explicit moderation review
- `is_official="true"` — lives in an official collection
- `is_library_member="true"` — in a curated published collection
- `is_container="true"` — a collection or dashboard; `read_resource` its `uri` to see members

When multiple results plausibly answer the question, prefer items with curator flags
(`is_library_member` > `is_verified` > `is_official`).

## Examples

<example>
<user_prompt>What dashboards do we have about customer health?</user_prompt>
<query>"customer health"</query>
<entity_types>["dashboard"]</entity_types>
</example>

<example>
<user_prompt>Find me revenue metrics in the marketing collection</user_prompt>
<query>"revenue"</query>
<entity_types>["metric"]</entity_types>
<collection_id>5</collection_id>
</example>

<example>
<user_prompt>Show me what tables exist for orders in the warehouse</user_prompt>
<query>"orders"</query>
<entity_types>["table", "model"]</entity_types>
<database_id>1</database_id>
</example>
