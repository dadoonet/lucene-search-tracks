# TrackSearch session and Elasticsearch LCD split

**Date:** 2026-09-21  
**Status:** approved design  
**Repo:** lucene-search-tracks  
**Branch:** `tracksearch-playground-switch`

## Problem

The Demo LCD shows the engine query (`Query#toString()` or a pasteable Elasticsearch curl) but not the Elasticsearch JSON response. Lucene and Elasticsearch still run search and facets as separate executions (two `_search` calls on ES, two Lucene passes). `search("Bob")` always materializes every hit (62), and the playground then slices to 25 in Java, so the printed ES body (`size: 0` + aggregations) does not match the hits table.

## Goal

One prepare → execute cycle per Demo search that returns hits, total, facets, the printable request, and (on Elasticsearch) the printable response.

The Elasticsearch LCD is a resizable horizontal split: request on top, syntax-highlighted JSON response below. Lucene has no JSON response, so its LCD stays a single query pane.

## Non-goals

- No pagination UI (no page 2, no `searchAfter`). `size` is a prepare parameter only.
- No Prism / highlight.js / extra CDN. JSON coloring is a small tokenizer in `playground.js`.
- Educational chapters stay Lucene-only on `PlaygroundLuceneHelper`.
- `suggest` is unchanged and is not part of the session.
- Do not merge the pedagogical Lucene index into `TrackSearchLuceneImpl`.

## Architecture

```
Demo UI  (one POST /api/search)
  → PlaygroundService
      → TrackSearch.prepareRequest(q, filters, mustNots, size=25)
           → TrackSearchSession
                printQuery()
                execute()          // one ES _search ; one Lucene DrillSideways/collector pass
                totalHits()
                getHits()
                getFacets()
                printResponse()    // ES pretty JSON ; "" on Lucene
```

`TrackSearch` stays the engine. A session is not shared across HTTP requests. Two concurrent Demo searches each own a session.

## `TrackSearch` API

Add:

```text
TrackSearchSession prepareRequest(q, filters, mustNots, size)
```

`size` must be `>= 1`; otherwise `IllegalArgumentException`.

Keep as **wrappers** that `prepareRequest(..., 10_000)`, `execute()`, then read the session (so existing contract tests stay valid):

```text
List<TrackHit> search(q, filters, mustNots)
TrackFacetsResult facets(q, filters, mustNots)
String printQuery(q, filters, mustNots)   // prepare only, no execute
```

`rebuild`, `suggest`, `close` do not change.

### `TrackSearchSession`

```text
String printQuery()
void execute()
int totalHits()
List<TrackHit> getHits()
TrackFacetsResult getFacets()
String printResponse()
```

- `printQuery()` is valid before `execute()` (the request is known).
- `execute()` twice re-runs and replaces stored results.
- `totalHits()`, `getHits()`, `getFacets()`, `printResponse()` before `execute()` throw `IllegalStateException`.
- `getHits()` is the page of at most `size` hits, in score order.
- `totalHits()` is the full match count (62 for `"Bob"` even when `size` is 25).
- `getFacets()` is the existing `TrackFacetsResult` (drill-sideways maps).
- `printResponse()` is pretty Elasticsearch JSON after execute; Lucene returns `""`.

## Execute: Elasticsearch (one `_search`)

```text
size:           session size
track_total_hits: true
query:          text + BPM/rating/year filters + must_nots
                (genre and key includes are NOT in the query)
post_filter:    genre + key includes (omitted when both are empty)
highlight:      pre <b> post </b>, number_of_fragments 0,
                fields title, artist, genre, album, label, comment
                (applies to the returned page)
aggs:           unchanged drill-sideways shape
                - genre: filter(key includes) + terms genre.raw size 50
                - key:   filter(genre includes) + terms key.raw size 50
                - drill: filter(genre+key includes)
                         bpm ranges from TrackFacets.bpmRanges()
                         rating terms
                         year histogram interval 10
```

`printQuery()` is pretty JSON of **this** `SearchRequest` body (not a second facet-only request).  
The playground still wraps that JSON with `ElasticsearchCurl.wrap(settings, json)` for the LCD. The API key stays out of `TrackSearch`.  
`printResponse()` is pretty JSON of **this** `SearchResponse` (`hits` + `aggregations`).  
`totalHits()` reads `hits.total`.

## Execute: Lucene (one collector pass)

- No genre/key drill: `FacetsCollectorManager.search(searcher, query, size, manager)` → `TopDocs` + facet collector.
- With genre/key drill: `DrillSideways.search(drillDown, size)` → hits filtered by drill, facet counts sideways.
- `UnifiedHighlighter` on that page only.
- `printQuery()` remains `Query#toString()` of the **hits** query (all filters, including genre/key).
- `printResponse()` is `""`.
- `totalHits()` is `TopDocs.totalHits.value` (the full match count, not `size`; 62 for `"Bob"`).

## Playground Demo

`PlaygroundService.search` (Demo path):

1. `prepareRequest(q, filters, mustNots, TOP_HITS)` with `TOP_HITS = 25`
2. `printQuery()`; if the engine is Elasticsearch, curl-wrap it
3. `execute()` once
4. Map `getHits()` → hit rows; `total` ← `totalHits()` (not `hits.size()`)
5. Map `getFacets()` → `dims` (same `FacetDim` list as today's `/api/facets`)
6. `response` ← `printResponse()`

`PlaygroundModels.SearchResponse` gains:

```text
String response     // printResponse(); "" on Lucene
List<FacetDim> dims // Demo chips; empty list when unused
```

Keep `query`, `total`, `hits`, `tookMs`, `tokens`, `q`.

`runDemo()` issues **one** `POST /api/search`. It must not `Promise.all` with `/api/facets`.  
`/api/facets` remains for the Facets chapter.

Lucene explain overlay on the Demo stays a Lucene-only extra pass after execute. It must not replace `query` / `response` on the LCD.

## LCD

Inside `.lcd` `#readout`, Demo chapter:

- **Lucene** (empty `response`): single `pre.demo-query` as today. No splitter.
- **Elasticsearch**: horizontal split, resizable
  - top: `pre.demo-query` (curl)
  - handle: same pointer pattern as `#col-split`, `row-resize`, persist ratio in `localStorage` (`playground-lcd-query-ratio`)
  - bottom: `pre.demo-response` with JSON syntax highlighting

JSON highlighting: a small tokenizer in `playground.js` (no new dependency). Colors stay on the LCD tokens: keys `phosphor-dim`, strings `phosphor`, numbers `cue`. Escape HTML before coloring.

Connect failures: unchanged — error text in the LCD, no split.

## Tests (TDD)

Contract (`TrackSearchContractTest`, both impls):

- `prepareRequest("Bob", …, 25)` + `execute()` → `getHits().size() == 25` and `totalHits() == 62`
- wrappers: `search("Bob")` still has size 62
- `getHits()` before `execute()` throws `IllegalStateException`
- one `execute()` fills hits **and** facets (Club = 26; drill-sideways genre/key unchanged)
- Lucene `printResponse()` is empty

Elasticsearch-only:

- `printQuery()` after `prepareRequest(..., 25)` contains `"size": 25` (or equivalent), aggregations, and `post_filter` when genre/key includes are set
- `printResponse()` after execute is JSON containing `hits` and `aggregations`

Playground:

- `/api/search` JSON includes `response` and `dims`
- Demo JS no longer fetches `/api/facets` in `runDemo`
- page source contains the LCD splitter + `demo-response` highlighter hooks
- Lucene Demo LCD still has `demo-query` and no response pane
- existing Bob/Club/mustNot counts stay green

No failing tests committed.

## Success criteria

- Demo Elasticsearch: one `_search` per keystroke/search. Curl on screen is that request; JSON below is that response.
- `size: 25` in the curl; table shows 25 rows; header still says 62 hits for `"Bob"`.
- Lucene LCD is not split.
- `search()` / `facets()` wrappers still satisfy the existing 62-hit contract.
