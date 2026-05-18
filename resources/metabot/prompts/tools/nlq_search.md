Hybrid keyword + semantic search for data sources you can query through natural language
(tables, models, metrics, saved questions, collections). Returns one XML element per hit,
each carrying a `uri` attribute and curation flags you feed back into `read_resource`
for details.

## When to use

- Finding the right data source before building a natural-language query.
- Looking for curated metrics, models, or saved questions that already answer the user's
  request.
- Use the navigation URIs on `read_resource` (`metabase://collections?tree=true`,
  `metabase://collection/{id}/items`) when you already know which container to enumerate
  — don't search to "list everything".

## How to query

`query` is a single string. Phrase it the way a human would: a short noun phrase or
question fragment.

- ✓ `"monthly active users"`, `"customer churn"`, `"revenue by region"`, `"top products"`
- ✗ `""` (empty), `"all metrics"`, `"everything"`

The backend runs your query through both keyword (full-text) and semantic (vector)
matching in parallel and fuses the two — you don't need to send paraphrases.

**Iteration is cheap.** If your first query misses, run it again with a different angle.
A short follow-up beats one broad bundled query.

## Filtering & scoping

`entity_types` — restrict to `table`, `model`, `metric`, `question`, or `collection`.
Leave empty to search broadly.

`database_id` — restrict to one database when the user mentions one or you want to
narrow.

`collection_id` — restrict to a collection AND its descendants. Use after a
`collection`-typed search hit, or after listing collections via
`metabase://collections?tree=true`.

`limit` — default 25, max 50.

## Reading the output

```xml
<results query="active users" total="9">
<metric id="19" name="Number of Active Users" uri="metabase://metric/19" is_official="true" database_id="1">
Collection: Examples
</metric>
<metabase_question id="16" name="Number of subscriptions" uri="metabase://question/16" database_id="1">
Collection: Examples
</metabase_question>
<metabase-model id="1" name="Orders + People" uri="metabase://model/1" is_verified="true" is_official="true" database_id="1">
Collection: Examples
</metabase-model>
<collection id="5" name="Engagement Reports" uri="metabase://collection/5" is_container="true"/>
...
</results>
```

Each result is an XML element. Curation attributes: `is_verified`, `is_official`,
`is_library_member`, `is_container`. Prefer items with curator flags
(`is_verified` > `is_official` > `is_library_member`) when multiple plausibly answer
the question.

When a result has `is_container="true"`, call `read_resource` on its `uri`
(`metabase://collection/{id}/items`) to enumerate members instead of re-searching.

## Examples

<example>
<user_prompt>What metrics do we have for sales performance?</user_prompt>
<query>"sales performance"</query>
<entity_types>["metric"]</entity_types>
</example>

<example>
<user_prompt>Find customer data in the marketing collection</user_prompt>
<query>"customer"</query>
<collection_id>5</collection_id>
</example>
