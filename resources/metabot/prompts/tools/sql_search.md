Hybrid keyword + semantic search for SQL-queryable data sources (tables and models)
within a single database. Returns one XML element per hit, each carrying a `uri` attribute
you feed back into `read_resource` to inspect schema before writing SQL.

## When to use

- Finding tables or models to write SQL against in the SQL editor.
- Verifying a table exists and seeing its schema before referencing it in a query.

## How to query

`query` is a single string. Phrase it the way a human would: a short noun phrase or table
name fragment.

- ✓ `"orders"`, `"customer accounts"`, `"product inventory"`, `"GitHub pull requests"`
- ✗ `""` (empty), `"all tables"`, `"everything"`

The backend runs your query through both keyword (full-text) and semantic (vector)
matching in parallel and fuses the two — you don't need to send paraphrases.

**Iteration is cheap.** If your first query misses, run it again with a different angle.
A short follow-up beats one broad bundled query.

## Required & optional args

`database_id` (required) — which warehouse to search. Use the database the user has
selected in the SQL editor.

`entity_types` (optional) — restrict to `table` or `model`. Leave empty to search both.

`limit` (optional) — default 25, max 50. Bump up when you want to see the broader
landscape; keep low for narrow lookups.

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
<table id="8" name="INVOICES" uri="metabase://table/8" database_id="1" fully_qualified_name="PUBLIC.INVOICES">
Confirmed payments from customers
</table>
...
</results>
```

Each result is an XML element. Curation attributes:
- `is_verified="true"` — explicit moderation review (strongest signal)
- `is_official="true"` — lives in an official collection
- `is_library_member="true"` — in a curated published collection

Pick the `uri` of a likely candidate and call `read_resource` (e.g.
`metabase://table/{id}/fields` or `metabase://model/{id}/sources`) to inspect schema
before writing SQL. Prefer curated items (`is_verified` > `is_official` >
`is_library_member`) when multiple plausibly answer the question.

## Examples

<context>User is in the SQL editor with database 123 selected.</context>

<example>
<user_prompt>What tables do we have for orders?</user_prompt>
<query>"orders"</query>
<database_id>123</database_id>
<entity_types>["table"]</entity_types>
</example>

<example>
<user_prompt>Find models for revenue reporting</user_prompt>
<query>"revenue reporting"</query>
<database_id>123</database_id>
<entity_types>["model"]</entity_types>
</example>
