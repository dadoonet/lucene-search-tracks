# TrackSearch Session and ES LCD Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `prepareRequest` → `execute` cycle returns hits, total, facets, the printable request, and (on Elasticsearch) the JSON response; the Demo LCD splits query/response only for ES.

**Architecture:** `TrackSearch.prepareRequest` returns a per-call `TrackSearchSession`. Lucene executes one collector/`DrillSideways` pass. Elasticsearch executes one `_search` (`size` + `post_filter` + aggregations + highlight). Demo `POST /api/search` runs that session once (`size = 25`). Existing `search()` / `facets()` / `printQuery(q,…)` become wrappers at `size = 10_000`.

**Tech Stack:** Java 25, Lucene 10.5.1, Elasticsearch Java client 9.5.4, JUnit 6, AssertJ, Javalin, Maven (no wrapper).

**Spec:** `docs/superpowers/specs/2026-09-21-tracksearch-session-lcd-design.md`

## Global Constraints

- Java 25 (`maven.compiler.release=25`).
- `size` on `prepareRequest` must be `>= 1` or throw `IllegalArgumentException`.
- Wrappers use `size = 10_000` so `search("Bob")` still returns 62 hits.
- Demo uses `PlaygroundService.TOP_HITS` (25). Header `total` is `totalHits()`, not `hits.size()`.
- Genre/key includes are drill-sideways; BPM/rating/year filters shrink those maps; `mustNot` hides buckets.
- Highlights use `<b>` / `</b>` on title/artist/genre/album/label/comment for the returned page.
- Elasticsearch curl wrapping stays in the playground (`ElasticsearchCurl`); API key never enters `TrackSearch`.
- Lucene `printResponse()` is always `""`. LCD split only when `response` is non-empty.
- Educational chapters stay on `PlaygroundLuceneHelper`.
- No Prism / highlight.js. No failing tests committed.
- Commit titles: single emoji + imperative, ≤ 72 chars (`⚙️`, `🥽`, `♻️`, `🎨`).

## File map

| File                                                                          | Role                                              |
| ----------------------------------------------------------------------------- | ------------------------------------------------- |
| `src/main/java/fr/pilato/test/lucene/TrackSearchSession.java`                 | Create: session interface                         |
| `src/main/java/fr/pilato/test/lucene/TrackSearch.java`                        | `prepareRequest` + wrapper defaults               |
| `src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java`               | Inner session; one Lucene pass                    |
| `src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java`        | Inner session; one `_search`                      |
| `src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java`             | Lifecycle, size 25 vs total 62, facets from execute |
| `src/test/java/fr/pilato/test/lucene/TrackSearchLuceneImplTest.java`           | `printResponse()` empty                           |
| `src/test/java/fr/pilato/test/lucene/TrackSearchElasticsearchImplTest.java`    | `printQuery` size/post_filter; `printResponse` JSON |
| `src/main/java/fr/pilato/test/lucene/playground/PlaygroundModels.java`         | `SearchResponse.response` + `dims`                |
| `src/main/java/fr/pilato/test/lucene/playground/PlaygroundService.java`        | One session per Demo search                       |
| `src/test/java/fr/pilato/test/lucene/playground/PlaygroundServiceTest.java`    | `dims` on search; total 62 / hits 25              |
| `src/test/java/fr/pilato/test/lucene/playground/PlaygroundAppTest.java`        | JS/CSS/API hooks                                  |
| `src/main/resources/public/playground.js`                                     | One `/api/search`; LCD split; JSON highlight      |
| `src/main/resources/public/playground.css`                                    | Split + JSON token colors                         |

---

### Task 1: Session API + Lucene one-pass execute

**Files:**
- Create: `src/main/java/fr/pilato/test/lucene/TrackSearchSession.java`
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearch.java`
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java`
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java` (compiling session that still uses two ES calls — replaced in Task 2)
- Modify: `src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java`
- Modify: `src/test/java/fr/pilato/test/lucene/TrackSearchLuceneImplTest.java`

**Interfaces:**
- Consumes: existing `query()`, `mix()`, `TrackDrillSideways`, `highlight()`, `TrackFacetsResult` mapping in `TrackSearchLuceneImpl`; existing ES `search`/`facets` helpers
- Produces: `TrackSearchSession` with `printQuery()`, `execute()`, `totalHits()`, `getHits()`, `getFacets()`, `printResponse()`; `TrackSearch.prepareRequest(String, Map<String, List<String>>, Map<String, List<String>>, int)`

- [ ] **Step 1: Write the failing tests**

Add to `TrackSearchContractTest`:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Test
void prepareRequest_rejectsNonPositiveSize() {
    assertThatThrownBy(() -> index().prepareRequest("Bob", Map.of(), Map.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void session_getHits_beforeExecuteThrows() throws Exception {
    TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
    assertThat(session.printQuery()).containsIgnoringCase("bob");
    assertThatThrownBy(session::getHits).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(session::getFacets).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(session::totalHits).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(session::printResponse).isInstanceOf(IllegalStateException.class);
}

@Test
void session_bobSize25_hasTwentyFiveHitsAndTotal62() throws Exception {
    TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
    session.execute();
    assertThat(session.getHits()).hasSize(25);
    assertThat(session.totalHits()).isEqualTo(62);
    assertThat(count(session.getFacets().genres(), "Club")).isEqualTo(26);
    assertThat(session.getFacets().bpm().get("120 – 130")).isEqualTo(52L);
}

@Test
void session_executeTwice_replacesResults() throws Exception {
    TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
    session.execute();
    session.execute();
    assertThat(session.getHits()).hasSize(25);
    assertThat(session.totalHits()).isEqualTo(62);
}
```

Add to `TrackSearchLuceneImplTest` (needs `@Test` + AssertJ imports):

```java
@Test
void printResponse_isEmpty() throws Exception {
    TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
    session.execute();
    assertThat(session.printResponse()).isEmpty();
}
```

Keep existing `search("Bob")` size-62 tests — they must keep passing via wrappers.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest,TrackSearchContractTest test`

Expected: COMPILE FAIL (`prepareRequest` / `TrackSearchSession` missing)

- [ ] **Step 3: Write the API + Lucene session; keep ES compiling**

Create `TrackSearchSession.java`:

```java
package fr.pilato.test.lucene;

import java.util.List;

public interface TrackSearchSession {
    String printQuery();
    void execute() throws Exception;
    int totalHits();
    List<TrackHit> getHits();
    TrackFacetsResult getFacets();
    String printResponse();
}
```

Replace `search` / `facets` / `printQuery` on `TrackSearch` with defaults plus `prepareRequest`:

```java
TrackSearchSession prepareRequest(
        String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size);

default List<TrackHit> search(
        String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
        throws Exception {
    TrackSearchSession session = prepareRequest(q, filters, mustNots, 10_000);
    session.execute();
    return session.getHits();
}

default TrackFacetsResult facets(
        String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
        throws Exception {
    TrackSearchSession session = prepareRequest(q, filters, mustNots, 10_000);
    session.execute();
    return session.getFacets();
}

default String printQuery(
        String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
        throws Exception {
    return prepareRequest(q, filters, mustNots, 10_000).printQuery();
}
```

**Lucene:** delete the standalone `search` / `facets` / `printQuery` methods. Add:

```java
@Override
public TrackSearchSession prepareRequest(
        String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size) {
    if (size < 1) {
        throw new IllegalArgumentException("size must be >= 1");
    }
    return new LuceneSession(q, filters, mustNots, size);
}
```

Inner `LuceneSession`:

- `printQuery()` → `query(q, filters, mustNots).toString()` (hits query, all filters). Valid before execute.
- `printResponse()` after execute → `""`.
- `execute()`:
  - Split filters into genre/key `drill` vs `baseFilters` (same split as today's `facets()`).
  - `base = query(q, baseFilters, mustNots)`.
  - If `drill` is empty: `FacetsCollectorManager.FacetsResult collected = FacetsCollectorManager.search(searcher, query(q, filters, mustNots), size, new FacetsCollectorManager());` then `TopDocs hits = collected.topDocs();` `Facets luceneFacets = mix(state, collected.facetsCollector());`
    Use the **hits** query (all filters) for the collector when there is no drill, so hits match wrappers.
  - If `drill` is non-empty: `DrillDownQuery drillDown = new DrillDownQuery(FACETS, base);` then `drill.forEach((dim, values) -> drillDown.add(dim, query("", Map.of(dim, values), Map.of())));` then `var result = new TrackDrillSideways(searcher, state).search(drillDown, size);` `TopDocs hits = result.hits;` `Facets luceneFacets = result.facets;`
  - Highlight with existing `highlight(searcher, query(q, filters, mustNots), hits)` (page is already `size`).
  - Map `ScoreDoc`s to `TrackHit` with the existing stored-field `id` loop.
  - `totalHits = Math.toIntExact(hits.totalHits.value)`.
  - Build `TrackFacetsResult` with the same `getAllChildren` / `toMap` / `decades` code as today's `facets()`.
- `requireExecuted()` throws `IllegalStateException` for `getHits` / `getFacets` / `totalHits` / `printResponse`.

**Elasticsearch (temporary two calls, Task 2 replaces this):** inner `ElasticsearchSession` so contract tests still run:

- `printQuery()` → pretty JSON of current `facetRequest` (size 0). Contract `printQuery_bobMentionsTheText` still passes.
- `execute()`:
  1. Hits: `client.search` with `.size(size)`, `.trackTotalHits(t -> t.enabled(true))`, `.query(query(q, filters, mustNots))`, same highlight fields as today (`<b>`, fragments 0). Map hits + snippets like today's `search()`.
  2. Facets: `client.search(facetRequest(q, filters, mustNots), Track.class)` and map aggregations like today's `facets()`.
  3. `totalHits` from `response.hits().total().value()` (cast to int).
- `printResponse()` after execute → `""` for this task (ES JSON comes in Task 2).
- Same `size < 1` / `requireExecuted()` rules.

Remove the old `search` / `facets` / `printQuery` overrides from the ES impl so the interface defaults run — **except** Task 2 will put real `prepareRequest` here; Task 1 already adds `prepareRequest` and deletes those overrides.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest test`

Expected: BUILD SUCCESS (Lucene contract + `printResponse_isEmpty`)

Run: `mvn -Dtest=TrackSearchElasticsearchImplTest test`

Expected: BUILD SUCCESS (same contract via two ES calls)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/TrackSearchSession.java \
  src/main/java/fr/pilato/test/lucene/TrackSearch.java \
  src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java \
  src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchLuceneImplTest.java
git commit -m "$(cat <<'EOF'
⚙️ Add TrackSearchSession with Lucene one-pass execute

Demo and tests need hits, total, and facets from one prepare/execute
cycle. Lucene now collects both in a single DrillSideways/collector pass;
wrappers keep size 10_000 so Bob is still 62 hits.
EOF
)"
```

---

### Task 2: Elasticsearch one `_search` + printQuery / printResponse

**Files:**
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java`
- Modify: `src/test/java/fr/pilato/test/lucene/TrackSearchElasticsearchImplTest.java`

**Interfaces:**
- Consumes: `TrackSearchSession` from Task 1; existing `query()`, `without()`, `scoped()`, `prettyJson()`, aggregation mappers
- Produces: `ElasticsearchSession.printQuery()` = pretty body of the **same** `SearchRequest` `execute()` sends; `printResponse()` = pretty JSON of that `SearchResponse`

- [ ] **Step 1: Write the failing tests**

Add to `TrackSearchElasticsearchImplTest`:

```java
@Test
void printQuery_size25_includesAggregations() throws Exception {
    TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
    assertThat(session.printQuery())
            .contains("\"size\"")
            .contains("25")
            .contains("aggregations")
            .contains("120 – 130")
            .contains("multi_match")
            .contains("highlight");
}

@Test
void printQuery_genreFilter_usesPostFilter() throws Exception {
    TrackSearchSession session = index().prepareRequest(
            "Bob", Map.of("genre", List.of("Club")), Map.of(), 25);
    assertThat(session.printQuery()).contains("post_filter");
}

@Test
void printResponse_afterExecute_hasHitsAndAggregations() throws Exception {
    TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
    session.execute();
    assertThat(session.printResponse())
            .contains("\"hits\"")
            .contains("aggregations")
            .contains("120 – 130");
    assertThat(session.getHits()).hasSize(25);
    assertThat(session.totalHits()).isEqualTo(62);
}
```

Update existing `printQuery_includesAggregationsAndBpmRanges` so it still passes against the wrapper (`size` 10_000 is fine; keep aggregations / `120 – 130` / `multi_match`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=TrackSearchElasticsearchImplTest#printQuery_genreFilter_usesPostFilter,TrackSearchElasticsearchImplTest#printResponse_afterExecute_hasHitsAndAggregations test`

Expected: FAIL (`printQuery` has no `post_filter`; `printResponse` is empty)

- [ ] **Step 3: One `SearchRequest` for execute and printQuery**

Replace `facetRequest(...)` with `searchRequest(q, filters, mustNots, size)`:

```java
private static SearchRequest searchRequest(
        String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size) {
    Map<String, List<String>> include = filters == null ? Map.of() : filters;
    Map<String, List<String>> exclude = mustNots == null ? Map.of() : mustNots;
    Map<String, List<String>> withoutGenreAndKey = without(include, TrackFacets.GENRE, TrackFacets.KEY);
    Map<String, List<String>> genreAndKey = new LinkedHashMap<>();
    putIfPresent(genreAndKey, include, TrackFacets.GENRE);
    putIfPresent(genreAndKey, include, TrackFacets.KEY);
    Query postFilter = filterQuery(genreAndKey);
    return SearchRequest.of(s -> {
        s.index(INDEX)
                .size(size)
                .trackTotalHits(t -> t.enabled(true))
                .query(query(q, withoutGenreAndKey, exclude))
                .highlight(h -> h
                        .preTags("<b>")
                        .postTags("</b>")
                        .numberOfFragments(0)
                        .fields(
                                NamedValue.of("title", HighlightField.of(f -> f)),
                                NamedValue.of("artist", HighlightField.of(f -> f)),
                                NamedValue.of("genre", HighlightField.of(f -> f)),
                                NamedValue.of("album", HighlightField.of(f -> f)),
                                NamedValue.of("label", HighlightField.of(f -> f)),
                                NamedValue.of("comment", HighlightField.of(f -> f))))
                .aggregations("genre", a -> a
                        .filter(scoped(include, TrackFacets.KEY))
                        .aggregations("genre", m -> m.terms(t -> t.field("genre.raw").size(50))))
                .aggregations("key", a -> a
                        .filter(scoped(include, TrackFacets.GENRE))
                        .aggregations("key", m -> m.terms(t -> t.field("key.raw").size(50))))
                .aggregations("drill", a -> a
                        .filter(scoped(include, TrackFacets.GENRE, TrackFacets.KEY))
                        .aggregations("bpm", m -> m.range(r -> {
                            r.field("bpm");
                            for (TrackFacets.NumericRange range : TrackFacets.bpmRanges()) {
                                r.ranges(rg -> {
                                    rg.key(range.label()).from(range.min());
                                    if (!Double.isInfinite(range.max())) {
                                        rg.to(range.max());
                                    }
                                    return rg;
                                });
                            }
                            return r;
                        }))
                        .aggregations("rating", m -> m.terms(t -> t.field("rating").size(10)))
                        .aggregations("year", m -> m.histogram(h -> h
                                .field("year")
                                .interval(10d)
                                .minDocCount(1))));
        if (postFilter != null) {
            s.postFilter(postFilter);
        }
        return s;
    });
}
```

`putIfPresent` copies a dim into `genreAndKey` only when values are non-empty. `filterQuery` already returns `null` for an empty map — **omit** `post_filter` then.

`ElasticsearchSession.printQuery()`:

```java
return prettyJson(JsonpUtils.toJsonString(searchRequest(q, filters, mustNots, size), JSONP));
```

`execute()`:

```java
SearchResponse<Track> response = client.search(searchRequest(q, filters, mustNots, size), Track.class);
this.printedResponse = prettyJson(JsonpUtils.toJsonString(response, JSONP));
this.totalHits = Math.toIntExact(response.hits().total().value());
List<TrackHit> hits = new ArrayList<>();
for (Hit<Track> hit : response.hits().hits()) {
    if (hit.source() != null) {
        hits.add(new TrackHit(hit.source(), score(hit), highlightMap(hit.highlight())));
    }
}
this.hits = List.copyOf(hits);
Map<String, Aggregate> aggs = response.aggregations();
Map<String, Aggregate> metrics = metrics(aggs.get("drill"));
this.facets = new TrackFacetsResult(
        nestedTerms(aggs.get("genre"), "genre"),
        rangeMap(metrics.get("bpm")),
        terms(metrics.get("rating")),
        decades(metrics.get("year")),
        nestedTerms(aggs.get("key"), "key"));
```

Delete the temporary second facet call and the old `facetRequest` method. Hits query must **not** put genre/key includes in `query` — they belong in `post_filter` only.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=TrackSearchElasticsearchImplTest test`

Expected: BUILD SUCCESS, including `postFilter_keepsOtherGenres` (Dance still counted when Club is selected)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchElasticsearchImplTest.java
git commit -m "$(cat <<'EOF'
⚙️ Run hits and facets in one Elasticsearch search

The LCD curl must be the request that produced the JSON below it.
One _search with size, post_filter, aggregations, and highlight
replaces the size-0 facet call plus a separate hits search.
EOF
)"
```

---

### Task 3: Playground Demo uses one session

**Files:**
- Modify: `src/main/java/fr/pilato/test/lucene/playground/PlaygroundModels.java`
- Modify: `src/main/java/fr/pilato/test/lucene/playground/PlaygroundService.java`
- Modify: `src/test/java/fr/pilato/test/lucene/playground/PlaygroundServiceTest.java`
- Modify: `src/test/java/fr/pilato/test/lucene/playground/PlaygroundAppTest.java`

**Interfaces:**
- Consumes: `engine.prepareRequest(q, filters, mustNots, TOP_HITS)` from Tasks 1–2; `ElasticsearchCurl.wrap`; existing `dim` / `children` / `ratings` / `decades` / `bpmBuckets` / `camelot` helpers
- Produces: `SearchResponse(q, tokens, query, response, total, hits, tookMs, dims)` where `response` is `printResponse()` (curl is still in `query` for ES) and `dims` is the same `List<FacetDim>` as `/api/facets`

- [ ] **Step 1: Write the failing tests**

In `PlaygroundServiceTest.search_bobMatchesBlogCountsAndExplainsTopHit`, after the existing total/hits/query assertions:

```java
assertThat(bob.dims()).isNotEmpty();
assertThat(countDim(bob.dims(), "genre", "Club")).isEqualTo(26);
assertThat(bob.response()).isEmpty(); // Lucene
```

Add helper in `PlaygroundServiceTest`:

```java
private static long countDim(List<FacetDim> dims, String name, String label) {
    return dims.stream()
            .filter(d -> name.equals(d.name()))
            .flatMap(d -> d.buckets().stream())
            .filter(b -> label.equals(b.label()))
            .mapToLong(FacetBucket::count)
            .findFirst()
            .orElse(0L);
}
```

In `PlaygroundAppTest.search_returnsExplainTreeKeys` (Lucene `/api/search` body):

```java
.contains("\"dims\"")
.contains("\"response\"")
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -Dtest=PlaygroundServiceTest#search_bobMatchesBlogCountsAndExplainsTopHit,PlaygroundAppTest#search_returnsExplainTreeKeys test`

Expected: COMPILE FAIL (`dims()` / `response()` missing on `SearchResponse`)

- [ ] **Step 3: Extend `SearchResponse` and run one session in `search()`**

```java
public record SearchResponse(
        String q,
        List<String> tokens,
        String query,
        String response,
        int total,
        List<SearchHitView> hits,
        long tookMs,
        List<FacetDim> dims) {}
```

`PlaygroundService.search`:

```java
TrackSearch engine = engine(backend);
TrackSearchSession session;
try {
    session = engine.prepareRequest(q, filters, mustNots, TOP_HITS);
    String printed = session.printQuery();
    if (engine != index) {
        printed = ElasticsearchCurl.wrap(elasticsearch.settings(), printed);
    }
    session.execute();
    List<TrackHit> page = session.getHits();
    int total = session.totalHits();
    String engineResponse = session.printResponse();
    List<FacetDim> dims = facetDims(session.getFacets());
    List<SearchHitView> hits = new ArrayList<>();
    for (TrackHit hit : page) {
        Track t = hit.track();
        hits.add(new SearchHitView(
                0, t.id(), t.title(), t.artist(), t.genre(), t.key(), t.bpm(), t.rating(), t.year(),
                hit.score(), null, null, hit.highlights()));
    }
    SearchResponse response = new SearchResponse(
            q, tokens, printed, engineResponse, total, List.copyOf(hits), 0, dims);
    if (engine == index) {
        response = withLuceneOverlay(response, request, q, filters, mustNots);
    }
    return withTook(response, start);
} catch (IOException e) {
    throw e;
} catch (Exception e) {
    throw new IOException(e);
}
```

Extract `facetDims(TrackFacetsResult raw)` returning the same five `FacetDim`s as `facets()` (`genre`, `rating`, `year`, `bpm`, `key`). `facets()` must call this helper so chip labels stay identical.

Update every `new SearchResponse(...)` in `withLuceneOverlay` and `withTook` to copy `response.query()`, `response.response()`, `response.dims()`. Overlay must **not** replace `query` or `response`.

`/api/facets` stays; it can keep using `engine.facets(...)` (wrapper, size 10_000).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -o -Dtest=PlaygroundServiceTest,PlaygroundAppTest test`

Expected: BUILD SUCCESS; Bob total 62, 25 hits, Club dim 26, Lucene `response` empty, explain tree still present

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/playground/PlaygroundModels.java \
  src/main/java/fr/pilato/test/lucene/playground/PlaygroundService.java \
  src/test/java/fr/pilato/test/lucene/playground/PlaygroundServiceTest.java \
  src/test/java/fr/pilato/test/lucene/playground/PlaygroundAppTest.java
git commit -m "$(cat <<'EOF'
⚙️ Drive Demo search from one TrackSearch session

The table, hit total, chips, and LCD must share one execute so ES size
25 and the 62-hit header cannot drift apart.
EOF
)"
```

---

### Task 4: LCD split + JSON highlighting (ES only)

**Files:**
- Modify: `src/main/resources/public/playground.js`
- Modify: `src/main/resources/public/playground.css`
- Modify: `src/test/java/fr/pilato/test/lucene/playground/PlaygroundAppTest.java`

**Interfaces:**
- Consumes: `search.query`, `search.response`, `search.dims` from Task 3
- Produces: ES LCD = horizontal resizable split (query curl / highlighted JSON); Lucene LCD = `pre.demo-query` only

- [ ] **Step 1: Write the failing tests**

In `PlaygroundAppTest.playgroundJs_demoWiresSearchFacetsAndChips`, add:

```java
.contains("search.dims")
.contains("demo-response")
.contains("highlightJson")
.contains("playground-lcd-query-ratio")
.contains("bindLcdSplit")
.doesNotContain("getJson(demoApi(\"/api/facets\")")
```

Keep `.contains("/api/facets")` — the Facets chapter still uses it. Only `runDemo` must stop calling it.

Add `PlaygroundAppTest.playgroundCss_demoSplitAndJsonTokens`:

```java
@Test
void playgroundCss_demoSplitAndJsonTokens() {
    JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
        var response = client.get("/playground.css");
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body().string())
                .contains(".demo-split")
                .contains("json-key")
                .contains("row-resize");
    });
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -Dtest=PlaygroundAppTest#playgroundJs_demoWiresSearchFacetsAndChips test`

Expected: FAIL (those strings are absent)

- [ ] **Step 3: Wire `runDemo` + LCD**

`runDemo()` — one fetch:

```javascript
const { filters, mustNots } = demoClauses();
const search = await getJson(demoApi("/api/search"), {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ q, filters, mustNots })
});
renderDemoChips();
renderDemoFacets(search.dims || []);
renderDemoHits(search);
setDemoReadout(search);
setCues(search.tokens || []);
const drillSideways = Boolean((filters.genre && filters.genre.length) || (filters.key && filters.key.length));
setSnippet(demoSnippet(q, search, { drillSideways }));
```

Keep the existing Lucene/ES Java sample in `demoSnippet`. It currently reads `facets.drillSideways`; pass `{ drillSideways }` instead.

`setDemoReadout(search)`:

```javascript
function setDemoReadout(search) {
  const engine = demoBackend() === "elasticsearch" ? "Elasticsearch" : "Lucene";
  const query = search.query || "";
  const response = search.response || "";
  if (!response) {
    readout(`
      <h3>${search.total} hits</h3>
      <p class="muted">${escapeHtml(engine)}</p>
      <pre class="demo-query">${escapeHtml(query)}</pre>`);
    return;
  }
  readout(`
    <h3>${search.total} hits</h3>
    <p class="muted">${escapeHtml(engine)}</p>
    <div class="demo-split">
      <pre class="demo-query">${escapeHtml(query)}</pre>
      <div class="splitter demo-split-handle" id="lcd-split" role="separator" aria-orientation="horizontal" aria-label="Resize query and response" tabindex="0"></div>
      <pre class="demo-response">${highlightJson(response)}</pre>
    </div>`);
  bindLcdSplit();
}
```

`highlightJson(json)`:

```javascript
function highlightJson(json) {
  const src = json || "";
  const token = /("(?:\\.|[^"\\])*")\s*:|("(?:\\.|[^"\\])*")|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b(?:true|false|null)\b/g;
  let out = "";
  let last = 0;
  let match;
  while ((match = token.exec(src))) {
    out += escapeHtml(src.slice(last, match.index));
    if (match[1] !== undefined) {
      out += `<span class="json-key">${escapeHtml(match[1])}</span>:`;
    } else if (match[2] !== undefined) {
      out += `<span class="json-string">${escapeHtml(match[2])}</span>`;
    } else if (match[0] === "true" || match[0] === "false" || match[0] === "null") {
      out += `<span class="json-literal">${match[0]}</span>`;
    } else {
      out += `<span class="json-number">${escapeHtml(match[0])}</span>`;
    }
    last = match.index + match[0].length;
  }
  return out + escapeHtml(src.slice(last));
}
```

`bindLcdSplit()` mirrors `bindColumnResize` on the Y axis:

- Read/write `localStorage["playground-lcd-query-ratio"]` (default `"0.45"`).
- Set `--lcd-query-ratio` on `.demo-split` as a percentage (`45%`).
- Clamp ratio to `[0.2, 0.8]`.
- `pointerdown` / `pointermove` using `clientY` vs `#readout` bounding rect.
- `body.is-row-resize` cursor `row-resize`.
- ArrowUp / ArrowDown adjust by ~0.04.

CSS:

```css
.demo-split {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1 1 auto;
  height: 100%;
}
.demo-split .demo-query {
  flex: 0 0 var(--lcd-query-ratio, 45%);
  min-height: 4rem;
  overflow: auto;
}
.demo-split-handle {
  flex: 0 0 10px;
  cursor: row-resize;
  margin: 0;
}
.demo-split .demo-response {
  flex: 1 1 auto;
  min-height: 4rem;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--phosphor);
}
.demo-response .json-key { color: var(--phosphor-dim); }
.demo-response .json-string { color: var(--phosphor); }
.demo-response .json-number { color: var(--cue); }
.demo-response .json-literal { color: var(--phosphor-dim); }
body.is-row-resize { cursor: row-resize; }
body.is-row-resize * { cursor: row-resize !important; }
```

`#readout` is already a flex child of `.lcd`. When `.demo-split` is present, `#readout` must be `display: flex; flex-direction: column;` so the split fills the panel. Apply that when the split exists (`.lcd:has(.demo-split) #readout { display: flex; flex-direction: column; }`) or always keep `#readout` as a column flex (it already is `flex: 1`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -o -Dtest=PlaygroundAppTest,PlaygroundServiceTest,TrackSearchLuceneImplTest test`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/public/playground.js \
  src/main/resources/public/playground.css \
  src/test/java/fr/pilato/test/lucene/playground/PlaygroundAppTest.java
git commit -m "$(cat <<'EOF'
🎨 Split the ES Demo LCD into query and JSON response

The curl is only useful next to the JSON it returns. A resizable
phosphor-highlighted pane keeps Lucene as a single query readout.
EOF
)"
```

---

## Spec coverage

| Spec requirement                                      | Task |
| ----------------------------------------------------- | ---- |
| `prepareRequest` / session lifecycle / IAE / ISE      | 1    |
| Wrappers `size = 10_000`, Bob 62 hits                 | 1    |
| Lucene one collector/`DrillSideways` pass             | 1    |
| Lucene `printQuery` = hits `Query#toString()`         | 1    |
| Lucene `printResponse` empty                          | 1    |
| ES one `_search` size + post_filter + aggs + highlight | 2    |
| ES `printQuery` / `printResponse` match that request  | 2    |
| Demo one `POST /api/search`, `totalHits`, `dims`      | 3    |
| Overlay does not replace LCD query/response           | 3    |
| `/api/facets` remains for Facets chapter              | 3    |
| ES LCD split + localStorage + JSON highlight          | 4    |
| Lucene LCD not split                                  | 4    |

## Type consistency

- Session methods: `printQuery()`, `execute()`, `totalHits()`, `getHits()`, `getFacets()`, `printResponse()`
- Wrapper size literal `10_000` (not a shared constant unless added on `TrackSearch`)
- `SearchResponse` field names: `query` (printed request / curl), `response` (ES JSON), `dims`, `total`, `hits`
- Storage key: `playground-lcd-query-ratio`
- CSS classes: `demo-split`, `demo-split-handle`, `demo-query`, `demo-response`, `json-key`, `json-string`, `json-number`, `json-literal`
