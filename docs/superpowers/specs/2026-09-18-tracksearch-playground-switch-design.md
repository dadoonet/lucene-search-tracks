# TrackSearch as the Demo engine

**Date:** 2026-09-18  
**Status:** approved design  
**Repo:** lucene-search-tracks

## Problem

The Demo tab looks like a Lucene / Elasticsearch switch. It is not.

- `TrackSearchLuceneImpl` and `TrackSearchElasticsearchImpl` implement a thin blog/contract API.
- The Demo Lucene path reimplements search and facets in `PlaygroundService` + `PlaygroundLuceneHelper`.
- The Demo Elasticsearch path reimplements search and facets in `PlaygroundElasticsearch`.
- `TrackSearchElasticsearchImpl` is only used by the playground for `rebuild` and `suggest`.
- BPM range definitions are duplicated (`TrackSearchLuceneImpl.bpmRanges()`, `playground.helpers.TrackFacets.bpmRanges()`). Elasticsearch Demo uses the full 16 ranges; `TrackSearchElasticsearchImpl.facets()` sends a single `120 – 130` bucket.

## Goal

One Lucene implementation, one Elasticsearch implementation, and a playground Demo that only switches between them.

Everything the Demo tab does (search, suggest, facets) must go through `TrackSearch`. Shared definitions such as BPM ranges live once.

## Non-goals

- Educational playground chapters (analyze, map, inverted index, Lucene explain) stay Lucene-only on `PlaygroundLuceneHelper`.
- No new engines.
- No change to the Rekordbox NDJSON corpus or to Testcontainers ES image selection beyond what those impls already use.
- Do not merge the pedagogical Lucene index into `TrackSearchLuceneImpl` in this work (two in-memory Lucene indexes at boot remain acceptable).

## Architecture

```
Demo UI
  → PlaygroundService (backend=lucene|elasticsearch)
      → TrackSearch
           ├─ TrackSearchLuceneImpl
           └─ TrackSearchElasticsearchImpl
      shared: TrackFacets (NumericRange, bpmRanges, decades, Camelot)
```

`PlaygroundElasticsearch` shrinks to connection lifecycle: URL/API key, `ElasticsearchClient`, `TrackSearchElasticsearchImpl.rebuild(corpus)`, status, disconnect.

`PlaygroundService` maps `TrackSearch` results to playground view models (`SearchHitView`, `FacetsResponse`, `SuggestHitView`). It must not rebuild queries, aggregations, or facet collectors for the Demo.

## Shared helpers

Move engine-neutral facet definitions from `fr.pilato.test.lucene.playground.helpers.TrackFacets` into `fr.pilato.test.lucene.TrackFacets`.

### `NumericRange`

```text
label : String
min   : double          // inclusive
max   : double          // exclusive; Double.POSITIVE_INFINITY for open end
```

Semantics match current Lucene `DoubleRange(..., from, true, to, false)` and Elasticsearch `range` aggregation (`from` inclusive, `to` exclusive).

### API on `TrackFacets`

- `bpmRanges()` — 16 ranges, same labels and bounds as today:
  - `0 – 80` → `[0, 80)`
  - `80 – 90` … `210 – 220` in steps of 10
  - `220+` → `[220, +∞)`
- `decadeLabel(int year)` — `2020–2029`, or `null` when `year < 1900`
- `decadeBounds(String label)` — inclusive `[from, to]` for a chip, or `null`
- `YEAR_MIN = 1900`
- `CAMELOT_CODES` — `1A`…`12B` in wheel order
- `GENRE`, `KEY` dimension names

Lucene-only `FacetsConfig` stays out of this class (keep it in the Lucene impl or in playground helpers used by educational tabs).

`TrackSearchLuceneImpl` maps `NumericRange` → `DoubleRange` when building `DoubleRangeFacetCounts`.  
`TrackSearchElasticsearchImpl` maps `NumericRange` → `range` aggregation buckets (`to` omitted when max is infinite).

Delete the private `bpmRanges()` copy in `TrackSearchLuceneImpl`. Playground code that still needs ranges (filter chips, educational snippets) calls the shared `TrackFacets`.

## `TrackSearch` API

Keep:

```text
void rebuild(List<Track> tracks)
List<TrackHit> search(q, filters, mustNots)
TrackFacetsResult facets(q, postFilters)
List<TrackSuggestion> suggest(prefix)
List<TrackSuggestion> suggest(prefix, scope)
void close()
```

No extra Demo-only methods. Enrich the result types instead.

### `TrackHit`

```text
Track track
float score
Map<String, String> highlights   // keys: title, artist, genre; empty map when no mark-up
```

Keep a 2-arg constructor `TrackHit(track, score)` that stores an empty highlights map so existing tests compile.

Both implementations highlight on `search()`. Contract tests may ignore highlights. The Demo uses them for the hits table.

`search()` returns **all** matching hits (contract: `Bob` → 62). The playground truncates display to `PlaygroundService.TOP_HITS` (25) and still reports `total` from the full list size.

### `TrackFacetsResult`

Replace the single-bucket fields with maps:

```text
Map<String, Long> genres
Map<String, Long> bpm        // keyed by NumericRange.label; zero-count buckets may be omitted
Map<String, Long> ratings    // "0"…"5"
Map<String, Long> years      // decade labels, e.g. "2020–2029"
Map<String, Long> keys       // Camelot codes
```

Presentation rules stay in the playground (skip empty BPM buckets, always show 5★→0★, Camelot wheel order). The impls must populate the maps so those rules can run without a second query.

`facets(q, postFilters)` keeps drill-sideways semantics:

- `q` scopes the base query.
- Genre values in `postFilters` do **not** shrink the `genres` map (other genres remain visible).
- Genre (and other) post-filters **do** shrink `bpm`, `ratings`, `years`, `keys`.
- Key include/exclude chips must not empty the `keys` map; other dimensions follow the same “filter the other facets, not the drilled dimension” pattern already used for genre in the Demo.

Existing contract assertions become map lookups, for example `facets.bpm().get("120 – 130") == 52` and a summed or labeled year bucket `2020–2029 == 15`.

## Playground Demo wiring

`PlaygroundService.search(..., backend)` / `suggest` / `facets`:

- `backend=elasticsearch` and ES ready → `TrackSearchElasticsearchImpl`
- otherwise → `TrackSearchLuceneImpl`

Do not call `PlaygroundLuceneHelper.search/facets` or `PlaygroundElasticsearch.search/facets` for the Demo.

`PlaygroundElasticsearch` remaining responsibilities:

- persist/connect URL + API key
- construct `ElasticsearchClient`
- `new TrackSearchElasticsearchImpl(client)` then `rebuild(corpus)`
- expose that `TrackSearch` instance to `PlaygroundService`
- disconnect/close

The Demo LCD is presentation-only: engine name, hit total, and query tokens (shared analyzer). It must not rebuild search queries or aggregations. Raw Lucene `Query#toString()` / Elasticsearch curl are not part of the `TrackSearch` contract in this work.

Educational tabs keep using `PlaygroundLuceneHelper` (second Lucene RAM index at boot).

## Elasticsearch aggregations

`TrackSearchElasticsearchImpl.facets()` must request **all** `TrackFacets.bpmRanges()`, not a single `120 – 130` range. Years use the shared decade labels (histogram or explicit ranges that produce the same keys as Lucene). Keys are a terms agg on `key`. Ratings remain terms on `rating`. Genre remains terms on `genre.raw`, outside the drill filter agg.

## Tests

- `TrackSearchContractTest` (Lucene + Testcontainers ES) is the source of truth.
- Update `bob_countsGenreBpmRatingYear` for map access; keep the same expected counts.
- Add contract coverage that both impls expose the `120 – 130` BPM label and at least the `2020–2029` year label; BPM maps are built from `TrackFacets.bpmRanges()`.
- Playground Demo tests keep asserting UI-facing JSON (hits, facet dims, backend switch) but must not depend on a parallel ES facet implementation.
- No failing tests committed.

## Error handling

Unchanged user-visible behaviour: if Elasticsearch is not configured or not ready, the Demo stays on Lucene and the ES engine button waits on the gear settings. Connect failures surface on the settings status line; they must not break Lucene Demo.

## Success criteria

- Changing the Demo engine switch changes only which `TrackSearch` implementation is called.
- One definition of BPM ranges; Lucene and ES Demo UIs show the same bucket labels.
- `TrackSearchElasticsearchImpl.facets("Bob")` can explain the 16 BPM rows in the Demo, not a single range.
- Educational Lucene chapters still work.
