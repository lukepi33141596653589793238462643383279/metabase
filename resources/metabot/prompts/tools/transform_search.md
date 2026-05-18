Hybrid keyword + semantic search for transforms (and the tables and models they read
from / write to). Returns one XML element per hit, each carrying a `uri` attribute you
feed back into `read_resource` for details, lineage, and source code.

## When to use

- Finding existing transforms before building a new one.
- Locating transforms that read from or write to a specific table.
- Discovering tables and models that could become transform sources.

## How to query

`query` is a single string. Phrase it the way a human would: a short noun phrase or
transform-name fragment.

- ✓ `"customer aggregation"`, `"daily orders rollup"`, `"revenue by region"`
- ✗ `""` (empty), `"all transforms"`, `"everything"`

The backend runs your query through both keyword (full-text) and semantic (vector)
matching in parallel and fuses the two — you don't need to send paraphrases.

**Iteration is cheap.** If your first query misses, run it again with a different angle.
A short follow-up beats one broad bundled query.

## Filtering & scoping

`entity_types` — restrict to `transform`, `table`, or `model`. Leave empty to search all.

`search_native_query` — when true, also matches against the actual SQL/Python source of
transforms. Use this when looking for transforms that reference specific tables, function
names, or implementation details (e.g. `"window function"`, `"CASE WHEN status"`). Defaults
to false.

`limit` — default 25, max 50.

## Reading the output

```xml
<results query="orders rollup" total="6">
<transform id="7" name="Daily Orders Rollup" uri="metabase://transform/7" is_verified="true" database_id="1">
Aggregates orders by day, writing to public.orders_daily
</transform>
<table id="2" name="ORDERS" uri="metabase://table/2" database_id="1" fully_qualified_name="PUBLIC.ORDERS">
Confirmed Sample Company orders
</table>
<metabase-model id="4" name="Orders summary" uri="metabase://model/4" database_id="1">
Collection: Examples
</metabase-model>
...
</results>
```

Each result is an XML element. Curation attributes: `is_verified`, `is_official`,
`is_library_member`.

For transforms specifically, follow up with `read_resource`:
- `metabase://transform/{id}` — full details and source query/Python
- `metabase://transform/{id}/sources` — tables this transform reads from
- `metabase://transform/{id}/target` — table this transform writes to

## Examples

<example>
<user_prompt>What transforms aggregate customer data?</user_prompt>
<query>"customer aggregation"</query>
<entity_types>["transform"]</entity_types>
</example>

<example>
<user_prompt>Find any transform that uses a window function</user_prompt>
<query>"window function"</query>
<entity_types>["transform"]</entity_types>
<search_native_query>true</search_native_query>
</example>
