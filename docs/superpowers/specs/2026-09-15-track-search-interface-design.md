# TrackSearch interface — Lucene and Elasticsearch

Companion follow-up on branch `elasticsearch`. Same `Track` + `tracks.ndjson`.
Search, facets, and autocomplete share one Java contract; each backend keeps
its own implementation so the blog can still show Lucene volume vs Elasticsearch
terseness.

Approved 2026-09-15: shared contract tests (option A), playground stays Lucene.

## Goals

- Callers (tests first) depend on `TrackSearch`, not on Lucene or ES types.
- Swap backend by constructing `TrackSearchLucene` or `TrackSearchElasticsearch`.
- Write Bob / Club / prefix / facet / suggest assertions **once**.
- Keep Lucene internals and the Javalin playground readable for Parts 1–5.

## Non-goals

- A generic search framework, DI, or runtime backend switch in the playground.
- Upsert / delete by id (still `rebuild` only).
- Hiding Lucene-only APIs the playground needs (`searcher()`, stored fields,
  `Explanation`). Those stay on `TrackSearchLucene`, not on the interface.
- Unifying mapper / analyzer tests (`TrackDocumentMapperTest`, analyze chapter).

## Shape

```
fr.pilato.test.lucene
  Track, TrackHit, TrackSuggestion, TrackDatasetLoader
  TrackSearch
  TrackFacetsResult

fr.pilato.test.lucene.lucene
  TrackSearchLucene          (today’s TrackSearchIndex + query/facet wiring)
  TrackAnalyzers, TrackDocumentMapper, TrackLuceneQueryBuilder, TrackFacets
  Lucene-only tests (mapper, RAM rebuild, Query.toString)

fr.pilato.test.lucene.elasticsearch
  TrackSearchElasticsearch   (today’s TrackElasticsearchIndex)

fr.pilato.test.lucene.playground
  unchanged behaviour; imports TrackSearchLucene (+ extra Lucene methods)
```

No shared abstract base class for the two implementations: the point of the
article is that the ES class stays short and the Lucene class stays long.

## Contract

```java
public interface TrackSearch extends AutoCloseable {
    void rebuild(List<Track> tracks) throws Exception;

    List<TrackHit> search(
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots) throws Exception;

    TrackFacetsResult facets(
            String q,
            Map<String, List<String>> postFilters) throws Exception;

    List<TrackSuggestion> suggest(String prefix) throws Exception;

    List<TrackSuggestion> suggest(String prefix, Collection<Track> scope)
            throws Exception;
}
```

`TrackFacetsResult` is a plain record in `fr.pilato.test.lucene` (genres map,
bpm 120–130 count, ratings map, 2020–2029 count). It must not mention Lucene
`FacetResult` or Elasticsearch `Aggregate`.

Semantics (already pinned by current tests):

| Call | Meaning |
|------|---------|
| `search` filters | Apply to **hits** (term on keyword / `.raw` / `key`) |
| `search` mustNots | Exclude those terms |
| `facets` with empty `postFilters` | Counts under `q` only |
| `facets` with `postFilters` | Hits conceptually filtered; **genre** buckets stay sideways (other genres visible); BPM / rating / year **narrow** to the post-filter |
| `suggest(prefix, List.of())` | Empty |
| `suggest(prefix)` / `null` scope | Whole dictionary |

Blank `q` + no filters + no mustNots → match-all (existing Lucene rule).

## Implementations

**`TrackSearchLucene`** — rename of `TrackSearchIndex`. `search` delegates to
`TrackLuceneQueryBuilder`. `facets` moves the current `TrackFacetsTest` /
playground mix (SSDV + range + long value) **behind** the interface, including
DrillSideways-equivalent behaviour when `postFilters` is non-empty. Extra
methods allowed: `searcher()`, `numDocs()`.

**`TrackSearchElasticsearch`** — rename of `TrackElasticsearchIndex`. Same
behaviour as today (template, `BulkIngester`, `multi_match` bool_prefix,
query + `post_filter` + filter agg for non-genre metrics, highlight suggest).
Constructor still takes `ElasticsearchClient`. Tests still build the client
with `ElasticsearchClient.of(...)` + Testcontainers HTTPS / CA.

## Tests

Shared assertions live on an abstract `TrackSearchContractTest`:

- Search: Bob 62; Bob+Club 26; Bob+Club minus 4A/4B 23; `bob sincla` hits;
  `bo sinclar` empty; `ouse` does not match House.
- Facets under Bob: Club 26, BPM 120–130 = 52, rating 5 = 13, 2020–2029 = 15.
- Post-filter genre Club: other genres (e.g. Dance) still > 0; BPM 120–130 < 52.
- Suggest: `club` → genre Club House + title In Da Club; Madonna → artist;
  empty scope → nothing.

Two concrete classes:

| Class | `@BeforeAll` |
|-------|----------------|
| `TrackSearchLuceneTest` | `new TrackSearchLucene()`, `rebuild(dataset)` |
| `TrackSearchElasticsearchTest` | `@Testcontainers` / `@Container` 9.5.2, `ElasticsearchClient.of(...)`, `new TrackSearchElasticsearch(client)` |

`TrackTestLog` stays the emoji narrative for both.

**Stay Lucene-only** (not on the contract class):

- `TrackDocumentMapperTest`
- RAM rebuild / `numDocs` (`TrackSearchIndexTest` today)
- `Query.toString()` contains `#` and `-`
- Playground HTTP tests

- Chip-as-filter: `search("club", genre=Club House)` vs `search("", genre=Club House)` — chip-only has at least as many hits. Both backends already implement this via `search`.

Delete duplicated methods from `TrackElasticsearchTest` once the contract class
covers them.

## Playground

Still Lucene. Point `PlaygroundService` at `TrackSearchLucene`. Do not add an
ES mode or a factory in the demo app.

## Blog contrast

`docs/elasticsearch-vs-lucene.md` stays the effort journal. After this change,
update line counts: interface + two impls, contract test vs two copies of
assertions. The ES impl must not grow a Lucene-style mapper.

## Error handling

Same as today: missing `tracks.ndjson` fails at load; Lucene indexes `""` for
null strings; ES serializes the `Track` bean as-is. `close()` on Lucene releases
directories; on ES it does not close the `ElasticsearchClient` (the test owns
the client).
