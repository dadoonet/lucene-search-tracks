# TrackSearch Playground Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Demo tab switches between `TrackSearchLuceneImpl` and `TrackSearchElasticsearchImpl`; BPM ranges and other facet definitions live once.

**Architecture:** Engine-neutral `TrackFacets` / `NumericRange` feed both implementations. `TrackFacetsResult` becomes maps (genre, bpm, rating, year decades, key). The playground Demo maps those results to UI views and must not rebuild queries or aggregations. Educational chapters keep `PlaygroundLuceneHelper`.

**Tech Stack:** Java 25, Lucene 10.5.1, Elasticsearch Java client 9.5.4, JUnit 6, AssertJ, Maven (no wrapper).

**Spec:** `docs/superpowers/specs/2026-09-18-tracksearch-playground-switch-design.md`

## Global Constraints

- Java 25 (`maven.compiler.release=21` is not used; this repo sets `maven.compiler.release=25`).
- Demo search / suggest / facets go through `TrackSearch` only.
- One BPM range list: 16 buckets, `[from, to)`, `220+` unbounded.
- Genre include and key include are drill-sideways (those maps do not shrink). BPM / rating / year filters are not sideways (they shrink those maps).
- `mustNot` applies to the base query and **does** hide buckets (e.g. exclude Club removes Club from `genres`).
- `search()` returns all hits; playground truncates display to 25.
- Search highlights use `<b>` / `</b>` (existing Demo / `PlaygroundServiceTest` markup).
- Educational tabs (analyze, map, inverted index, Lucene explain) stay on `PlaygroundLuceneHelper`.
- Two Lucene RAM indexes at boot remain OK.
- No failing tests committed.
- Commit titles: single emoji + imperative, ≤ 72 chars (repo style: `♻️`, `🥽`, `⚙️`, `✏️`).

## File map

| File | Role |
|---|---|
| `src/main/java/fr/pilato/test/lucene/TrackFacets.java` | Create: engine-neutral ranges, decades, Camelot |
| `src/test/java/fr/pilato/test/lucene/TrackFacetsTest.java` | Create: unit tests for ranges/decades |
| `src/main/java/fr/pilato/test/lucene/playground/helpers/TrackFacets.java` | Keep `FacetsConfig`; delegate bpm/decades/Camelot to shared class; `bpmRanges()` still returns Lucene `DoubleRange[]` for educational code |
| `src/main/java/fr/pilato/test/lucene/TrackHit.java` | Add `highlights`; 2-arg ctor |
| `src/main/java/fr/pilato/test/lucene/TrackFacetsResult.java` | Maps instead of single buckets |
| `src/main/java/fr/pilato/test/lucene/TrackSearch.java` | `facets(q, filters, mustNots)` + 2-arg default |
| `src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java` | Shared ranges, key facet, decade maps, chip filters, highlights |
| `src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java` | All BPM ranges, key/year maps, chip filters, highlights |
| `src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java` | Map lookups + key/bpm labels |
| `src/main/java/fr/pilato/test/lucene/playground/PlaygroundService.java` | Demo switch → `TrackSearch` |
| `src/main/java/fr/pilato/test/lucene/playground/PlaygroundElasticsearch.java` | Connection + `TrackSearch` accessor only |
| `README.md` | One sentence if the Demo switch description is now accurate |

---

### Task 1: Shared `TrackFacets` + `NumericRange`

**Files:**
- Create: `src/main/java/fr/pilato/test/lucene/TrackFacets.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackFacetsTest.java`
- Modify: `src/main/java/fr/pilato/test/lucene/playground/helpers/TrackFacets.java`

**Interfaces:**
- Consumes: current labels/bounds from `playground.helpers.TrackFacets.bpmRanges()` / `decadeLabel` / `CAMELOT_CODES`
- Produces: `fr.pilato.test.lucene.TrackFacets.NumericRange(String label, double min, double max)`, `bpmRanges()`, `decadeLabel(int)`, `decadeBounds(String)`, `YEAR_MIN`, `CAMELOT_CODES`, `GENRE`, `KEY`

- [ ] **Step 1: Write the failing test**

```java
package fr.pilato.test.lucene;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackFacetsTest {

    @Test
    void bpmRanges_areSixteenHalfOpenBuckets() {
        TrackFacets.NumericRange[] ranges = TrackFacets.bpmRanges();
        assertThat(ranges).hasSize(16);
        assertThat(ranges[0]).isEqualTo(new TrackFacets.NumericRange("0 – 80", 0.0, 80.0));
        assertThat(ranges[5]).isEqualTo(new TrackFacets.NumericRange("120 – 130", 120.0, 130.0));
        assertThat(ranges[15].label()).isEqualTo("220+");
        assertThat(ranges[15].min()).isEqualTo(220.0);
        assertThat(Double.isInfinite(ranges[15].max())).isTrue();
    }

    @Test
    void decadeLabel_skipsJunkYears() {
        assertThat(TrackFacets.decadeLabel(0)).isNull();
        assertThat(TrackFacets.decadeLabel(1899)).isNull();
        assertThat(TrackFacets.decadeLabel(2024)).isEqualTo("2020–2029");
        assertThat(TrackFacets.decadeBounds("2020–2029")).containsExactly(2020, 2029);
        assertThat(TrackFacets.decadeBounds("0–9")).isNull();
    }

    @Test
    void camelot_hasTwentyFourSlots() {
        assertThat(TrackFacets.CAMELOT_CODES).hasSize(24).startsWith("1A", "1B").endsWith("12B");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -Dtest=TrackFacetsTest test`

Expected: COMPILE FAIL (`package fr.pilato.test.lucene` has no `TrackFacets`)

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/fr/pilato/test/lucene/TrackFacets.java`:

```java
package fr.pilato.test.lucene;

import java.util.List;

public final class TrackFacets {
    public static final String GENRE = "genre";
    public static final String KEY = "key";
    public static final int YEAR_MIN = 1900;
    public static final List<String> CAMELOT_CODES = List.of(
            "1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B",
            "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B",
            "9A", "9B", "10A", "10B", "11A", "11B", "12A", "12B");

    public record NumericRange(String label, double min, double max) {}

    private TrackFacets() {}

    public static NumericRange[] bpmRanges() {
        NumericRange[] ranges = new NumericRange[16];
        ranges[0] = new NumericRange("0 – 80", 0.0, 80.0);
        for (int i = 0; i < 14; i++) {
            double from = 80.0 + (i * 10.0);
            double to = from + 10.0;
            ranges[i + 1] = new NumericRange(((int) from) + " – " + ((int) to), from, to);
        }
        ranges[15] = new NumericRange("220+", 220.0, Double.POSITIVE_INFINITY);
        return ranges;
    }

    public static String decadeLabel(int year) {
        if (year < YEAR_MIN) {
            return null;
        }
        int decade = (year / 10) * 10;
        return decade + "–" + (decade + 9);
    }

    public static int[] decadeBounds(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String[] parts = label.split("–", 2);
        if (parts.length != 2) {
            parts = label.split("-", 2);
        }
        if (parts.length != 2) {
            return null;
        }
        try {
            int from = Integer.parseInt(parts[0].trim());
            int to = Integer.parseInt(parts[1].trim());
            if (from < YEAR_MIN || to < from) {
                return null;
            }
            return new int[] {from, to};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

Point `playground.helpers.TrackFacets` at it: keep `config()`, keep `bpmRanges()` returning `DoubleRange[]` built from `fr.pilato.test.lucene.TrackFacets.bpmRanges()`, and delegate `decadeLabel` / `decadeBounds` / `CAMELOT_CODES` / `YEAR_MIN` / `GENRE` / `KEY` so educational Lucene code does not change imports.

```java
public static DoubleRange[] bpmRanges() {
    var src = fr.pilato.test.lucene.TrackFacets.bpmRanges();
    DoubleRange[] ranges = new DoubleRange[src.length];
    for (int i = 0; i < src.length; i++) {
        var r = src[i];
        ranges[i] = new DoubleRange(r.label(), r.min(), true, r.max(), false);
    }
    return ranges;
}
```

Delete the duplicated loop body.

- [ ] **Step 4: Run tests**

Run: `mvn -o -Dtest=TrackFacetsTest,PlaygroundServiceTest test`

Expected: PASS (Docker not required)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/TrackFacets.java \
  src/test/java/fr/pilato/test/lucene/TrackFacetsTest.java \
  src/main/java/fr/pilato/test/lucene/playground/helpers/TrackFacets.java
git commit -m "$(cat <<'EOF'
♻️ Share BPM ranges and decades on TrackFacets

Lucene and Elasticsearch must count the same buckets; the playground helper
kept a second copy and the ES contract impl used a single 120–130 range.
EOF
)"
```

---

### Task 2: `TrackHit` highlights

**Files:**
- Modify: `src/main/java/fr/pilato/test/lucene/TrackHit.java`
- Modify: every `new TrackHit(...)` still compiles via the 2-arg constructor

**Interfaces:**
- Consumes: current `TrackHit(Track, float)`
- Produces: `TrackHit(Track track, float score, Map<String, String> highlights)` with compact ctor `TrackHit(Track, float)` → `Map.of()`

- [ ] **Step 1: Write a failing compile-level assertion in `TrackFacetsTest` is the wrong place. Add to `TrackSearchContractTest`:**

```java
@Test
void search_hitHasEmptyHighlightsByDefault() throws Exception {
    TrackHit hit = index().search("Bob", Map.of(), Map.of()).getFirst();
    assertThat(hit.highlights()).isNotNull();
}
```

This fails to compile until `highlights()` exists.

- [ ] **Step 2: Confirm compile failure**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest#search_hitHasEmptyHighlightsByDefault test`

Expected: COMPILE FAIL `cannot find symbol highlights()`

- [ ] **Step 3: Change the record**

```java
package fr.pilato.test.lucene;

import java.util.Map;

public record TrackHit(Track track, float score, Map<String, String> highlights) {
    public TrackHit(Track track, float score) {
        this(track, score, Map.of());
    }

    public TrackHit {
        highlights = highlights == null ? Map.of() : Map.copyOf(highlights);
    }
}
```

Leave implementations using `new TrackHit(track, score)` for this task (empty highlights).

- [ ] **Step 4: Run**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest,TrackFacetsTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/TrackHit.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java
git commit -m "$(cat <<'EOF'
⚙️ Add highlights to TrackHit

The Demo table marks title/artist/genre; the contract type had no place
for that markup, so the playground highlighted beside TrackSearch.
EOF
)"
```

---

### Task 3: `TrackFacetsResult` maps + both facet implementations

**Files:**
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearch.java`
- Modify: `src/main/java/fr/pilato/test/lucene/TrackFacetsResult.java`
- Modify: `src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java`
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java`
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java`
- Modify: `src/main/java/fr/pilato/test/lucene/playground/helpers/PlaygroundLuceneHelper.java` (`toResult` if it still builds `TrackFacetsResult`)

**Interfaces:**
- Consumes: `TrackFacets.bpmRanges()`, `decadeLabel`, `CAMELOT_CODES`
- Produces:

```java
TrackFacetsResult facets(String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
    throws Exception;

default TrackFacetsResult facets(String q, Map<String, List<String>> postFilters) throws Exception {
    return facets(q, postFilters == null ? Map.of() : postFilters, Map.of());
}

public record TrackFacetsResult(
        Map<String, Long> genres,
        Map<String, Long> bpm,
        Map<String, Long> ratings,
        Map<String, Long> years,
        Map<String, Long> keys) {}
```

**Facet semantics (both impls):**

- Base query = `q` + `mustNots` + filters **except** `genre` and `key` includes.
- `genre` includes → drill-sideways on genre only (`genres` still lists Dance when Club is selected).
- `key` includes → drill-sideways on key only (`keys` 4A count unchanged when filtering 4B).
- BPM / rating / year includes stay on the base query (other BPM buckets go to 0).
- Index a Lucene SSDV facet field `"key"` (same pattern as `"genre"`) so `getAllChildren("key")` works.
- Years: group Lucene per-year counts with `TrackFacets.decadeLabel`; omit junk (`null` label). ES: range agg (or histogram + same labeling) with keys like `2020–2029`.
- BPM: iterate `TrackFacets.bpmRanges()`. ES `to` omitted when `max` is infinite. Delete `TrackSearchLuceneImpl.bpmRanges()`.
- Ratings map keys `"0"`…`"5"`. Zero counts may be omitted; playground will re-fill stars.

- [ ] **Step 1: Change the contract test first**

Replace `bob_countsGenreBpmRatingYear` accessors:

```java
assertThat(facets.bpm().get("120 – 130")).isEqualTo(52L);
assertThat(facets.years().get("2020–2029")).isEqualTo(15L);
assertThat(facets.keys()).isNotEmpty();
assertThat(facets.bpm().keySet()).contains("120 – 130");
```

In `postFilter_keepsOtherGenres`:

```java
assertThat(facets.bpm().getOrDefault("120 – 130", 0L)).isLessThan(52L);
```

- [ ] **Step 2: Run Lucene contract to see red**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest test`

Expected: COMPILE FAIL (`bpm120to130()`, 4-arg `TrackFacetsResult`)

- [ ] **Step 3: Implement maps in both classes**

**Lucene `mix()`:** add `byDim.put("key", new SortedSetDocValuesFacetCounts(state, collector))`. In `toDocument`, `doc.add(new SortedSetDocValuesFacetField("key", t.key()))` when key is non-blank.

Build `TrackFacetsResult` from `toMap(genres)`, BPM `toMap` (labels already on ranges), `toMap(rating)`, `decades(year FacetResult)`, `toMap(keys)`.

`decades(FacetResult year)`:

```java
Map<String, Long> out = new LinkedHashMap<>();
for (LabelAndValue lv : year.labelValues) {
    String label = TrackFacets.decadeLabel(Integer.parseInt(lv.label));
    if (label != null) {
        out.merge(label, lv.value.longValue(), Long::sum);
    }
}
return out;
```

**DrillDownQuery:** only add `genre` and `key` entries from `filters` as drill dims. Other filter dims + all `mustNots` go into `query(q, baseFilters, mustNots)`.

**ES `facets()`:** `aggregations("genre", terms genre.raw)`, `aggregations("key", terms key)` at top level (or a second filter that excludes only the drilled dim). Nested `drill` filter query = non-genre-non-key filters + mustNots (and genre/key includes when we want metrics scoped). Simpler mirror of current playground ES:

- `query` = `query(q, withoutGenreAndKey, mustNots)`
- `filter` agg scoped to genre+key includes (and any remaining filters already in query)
- sub-aggs: bpm (all `NumericRange`s), rating terms, year ranges/histogram mapped to decade labels
- top-level genre terms + key terms on the unfiltered-for-that-dim view (genre agg not inside genre filter; key agg not inside key filter)

Populate `TrackFacetsResult` from those aggs (`rangeCount` becomes a full map from all buckets).

Delete `.ranges(rg -> rg.key("120 – 130").from(120d).to(130d))`.

Update `PlaygroundLuceneHelper.toResult` to the 5-map constructor so it still compiles (educational helper can keep returning thin maps; Demo will not call it after Task 5).

- [ ] **Step 4: Run contract tests**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest test`

Expected: PASS

Run: `mvn -Dtest=TrackSearchElasticsearchImplTest test`

Expected: PASS (needs Docker)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/TrackSearch.java \
  src/main/java/fr/pilato/test/lucene/TrackFacetsResult.java \
  src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java \
  src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java \
  src/main/java/fr/pilato/test/lucene/playground/helpers/PlaygroundLuceneHelper.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java
git commit -m "$(cat <<'EOF'
⚙️ Return full facet maps from TrackSearch

The Demo needs every BPM bucket, decades, and Camelot keys; a single
120–130 count could not drive the switch.
EOF
)"
```

---

### Task 4: Chip filters + search highlights on both impls

**Files:**
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java` (`fieldFilter` / `search`)
- Modify: `src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java` (`term` / `search` highlight)
- Modify: `src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java`

**Interfaces:**
- Consumes: `TrackFacets.bpmRanges()`, `decadeBounds(label)`
- Produces: `search()` honors `bpm` / `rating` / `year` chips; each `TrackHit.highlights()` may contain `title` / `artist` / `genre` with `<b>` tags

- [ ] **Step 1: Failing contract tests**

```java
@Test
void yearJunkChip_matchesNothing() throws Exception {
    assertThat(index().search("", Map.of("year", List.of("0–9")), Map.of())).isEmpty();
}

@Test
void bpmChip_keepsOnlyThatRange() throws Exception {
    List<TrackHit> hits = index().search("Bob", Map.of("bpm", List.of("120 – 130")), Map.of());
    assertThat(hits).isNotEmpty().allMatch(h -> h.track().bpm() >= 120 && h.track().bpm() < 130);
}

@Test
void bob_highlightsTitle() throws Exception {
    assertThat(index().search("Bob", Map.of(), Map.of()))
            .anySatisfy(hit -> {
                if ("Free (Bob Sinclar Remix)".equals(hit.track().title())) {
                    assertThat(hit.highlights().get("title")).containsIgnoringCase("bob");
                    assertThat(hit.highlights().get("title")).contains("<b>");
                }
            });
}
```

- [ ] **Step 2: Run Lucene contract — red**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest#bpmChip_keepsOnlyThatRange test`

Expected: FAIL (filter ignored → hits with BPM outside `[120,130)`)

- [ ] **Step 3: Implement filters + highlights**

Lucene `fieldFilter` switch, same idea as `TrackLuceneQueryBuilder.fieldQuery`:

- `bpm` → `DoubleField.newRangeQuery("bpm", min, maxIsInf ? +∞ : Math.nextDown(max))` for matching `NumericRange.label`
- `rating` → `IntField.newExactQuery("rating", parseInt)`
- `year` → if `decadeBounds` is null, `MatchNoDocsQuery`; else `IntField.newRangeQuery("year", from, to)`

After `TopDocs`, highlight with `UnifiedHighlighter` (copy the approach in `playground/helpers/TrackHighlighter.java`: fields title/artist/genre, `WholeBreakIterator`, max length 10_000). Pre/post tags default to `<b>` if the highlighter uses HTML; UnifiedHighlighter wraps with `<b>` when using the default? **Force tags:** the playground highlighter currently emits `<b>` via its caller (`TrackHighlighter` uses default UnifiedHighlighter which uses `<b>`). Match that. Pass `Map` per hit into `new TrackHit(track, score, highlights)`.

ES `fieldQuery` (replace keyword-only `term()` for these dims):

- `bpm` → `range` number `gte`/`lt` from `NumericRange`
- `rating` → `term` on `rating`
- `year` → `range` on `year` from `decadeBounds`; `match_none` when bounds are null

`search()`: add `.highlight(h -> h.preTags("<b>").postTags("</b>").numberOfFragments(0).fields(title, artist, genre))` and copy `hit.highlight()` into `TrackHit`.

- [ ] **Step 4: Run contracts**

Run: `mvn -o -Dtest=TrackSearchLuceneImplTest test`

Expected: PASS

Run: `mvn -Dtest=TrackSearchElasticsearchImplTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/TrackSearchLuceneImpl.java \
  src/main/java/fr/pilato/test/lucene/TrackSearchElasticsearchImpl.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchContractTest.java
git commit -m "$(cat <<'EOF'
⚙️ Honor Demo chips and highlights in TrackSearch

BPM, year, and rating chips lived only in the playground query builders,
so switching the Demo to TrackSearch would have dropped filters.
EOF
)"
```

---

### Task 5: Playground Demo calls `TrackSearch` only

**Files:**
- Modify: `src/main/java/fr/pilato/test/lucene/playground/PlaygroundElasticsearch.java`
- Modify: `src/main/java/fr/pilato/test/lucene/playground/PlaygroundService.java`
- Modify: `src/test/java/fr/pilato/test/lucene/playground/PlaygroundServiceTest.java` (query/explain/LCD)
- Modify: `src/main/resources/public/playground.js` (`setDemoReadout` — tokens + engine + total, no curl/DrillSideways requirement)
- Modify: `README.md` if the Demo paragraph still claims a second ES query stack

**Interfaces:**
- Consumes: `TrackSearch.search/facets/suggest`
- Produces: `PlaygroundElasticsearch.trackSearch()` → `TrackSearch` (null when not ready); `PlaygroundService` maps to `SearchResponse` / `FacetsResponse` / `SuggestResponse`

- [ ] **Step 1: Failing characterization — keep playground tests, then change production so ES/Lucene Demo no longer has private aggs**

Add on `PlaygroundElasticsearch`:

```java
synchronized TrackSearch trackSearch() {
    return ready ? index : null;
}
```

Delete (or stop calling) `PlaygroundElasticsearch.search`, `facets`, and the private query/agg helpers used only by them. Keep `connect` / `rebuild` / `suggest` can become `index.suggest`. Keep `status` / `disconnect` / `close`.

In `PlaygroundService`:

```java
private TrackSearch engine(String backend) {
    if (isElasticsearch(backend) && elasticsearch.ready()) {
        TrackSearch es = elasticsearch.trackSearch();
        if (es != null) {
            return es;
        }
    }
    return index;
}

public SearchResponse search(SearchRequest request, String backend) throws IOException {
    TrackSearch engine = engine(backend);
    String q = request == null || request.q() == null ? "" : request.q();
    Map<String, List<String>> filters = request == null || request.filters() == null ? Map.of() : request.filters();
    Map<String, List<String>> mustNots = request == null || request.mustNots() == null ? Map.of() : request.mustNots();
    long start = System.nanoTime();
    List<TrackHit> all = /* catch Exception and wrap as IOException */ engine.search(q, filters, mustNots);
    int total = all.size();
    List<TrackHit> page = all.size() > TOP_HITS ? all.subList(0, TOP_HITS) : all;
    List<SearchHitView> hits = new ArrayList<>();
    for (TrackHit hit : page) {
        Track t = hit.track();
        hits.add(new SearchHitView(
                0, t.id(), t.title(), t.artist(), t.genre(), t.key(), t.bpm(), t.rating(), t.year(),
                hit.score(), null, null, hit.highlights()));
    }
    List<String> tokens = TrackAnalyzers.tokenize(q);
    String query = String.join(" ", tokens);
    SearchResponse response = new SearchResponse(q, tokens, query, total, List.copyOf(hits), 0);
    return withTook(response, start);
}
```

**Lucene Search chapter explain:** after building `hits`, if `engine == index` and `request.explainDoc() != null` (or always for the first hit when the Search chapter needs it), attach explain from `luceneIndex` + `TrackLuceneQueryBuilder` + `PlaygroundExplain` using the same `q/filters/mustNots`. Do **not** use `luceneIndex` for the hit list. If `PlaygroundServiceTest.search_bobMatchesBlogCountsAndExplainsTopHit` still requires `query()` to contain `title:bob`, set `query` from `TrackLuceneQueryBuilder.buildStructured(...).toString()` only when `engine == index` (pedagogical overlay, not a second hit list).

**Suggest:** `engine(backend).suggest(prefix)` for both backends (drop `elasticsearch.suggest` wrapper).

**Facets:**

```java
TrackFacetsResult raw = engine.facets(q, include, exclude);
boolean sideways = !include.getOrDefault("genre", List.of()).isEmpty();
return new FacetsResponse(q, "", sideways, String.join(", ", genres), List.of(
        dim("genre", "🏷️", children(raw.genres(), 12)),
        dim("rating", "⭐", ratings(raw.ratings())),
        dim("year", "📅", decades(raw.years())),
        dim("bpm", "⏱", bpmBuckets(raw.bpm())),
        dim("key", "🎹", camelot(raw.keys()))),
    engine == index ? facetRewriteFromHelper() : new FacetRewrite(List.of(), List.of()));
```

Presentation helpers stay in `PlaygroundService`: skip empty BPM, force 5→0 stars, Camelot order including zeros, year sort. They read maps; they must not query Lucene/ES.

Remove `PlaygroundService.searchLucene`, `mix`, `camelotKeys(IndexSearcher, Query)`, and the DrillSideways block from `facets`.

Keep `facetRewrite()` for the Lucene facets lesson (`facets_showsFacetsConfigRewrite`) via `luceneIndex` documents, not via Demo search.

- [ ] **Step 2: Update tests that required curl / `title:bob` as the Demo engine string**

`PlaygroundServiceTest.search_bobMatchesBlogCountsAndExplainsTopHit`: keep `total` 62/26/23. If query overlay remains Lucene `toString()`, keep `contains("title:bob")` for default lucene backend. Do not require curl.

`PlaygroundAppTest` ES search: still `?backend=elasticsearch`; assertions on totals/hits, not on a duplicated agg body.

`setDemoReadout` in `playground.js`: show `search.total`, engine name, `search.tokens`. Stop requiring `search.query` to be a curl script.

- [ ] **Step 3: Run playground + Lucene contract**

Run: `mvn -o -Dtest=PlaygroundServiceTest,PlaygroundAppTest,TrackSearchLuceneImplTest,TrackFacetsTest test`

Expected: PASS

Run: `mvn test` (includes Testcontainers ES)

Expected: PASS

- [ ] **Step 4: Grep that Demo no longer builds ES range aggs**

Run: `rg "120 – 130" src/main/java --glob '*.java'`

Expected: no match in `TrackSearchElasticsearchImpl` as a **single** hardcoded range; matches only as a label inside a loop over `TrackFacets.bpmRanges()` (and tests/logs). `PlaygroundElasticsearch.java` must not contain `.aggregations("bpm"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/pilato/test/lucene/playground \
  src/test/java/fr/pilato/test/lucene/playground \
  src/main/resources/public/playground.js \
  README.md
git commit -m "$(cat <<'EOF'
♻️ Drive the Demo only through TrackSearch

The Lucene/Elasticsearch switch was a second query stack; the Demo now
calls the same implementations as the contract tests.
EOF
)"
```

---

## Self-review (spec coverage)

| Spec requirement | Task |
|---|---|
| Shared `NumericRange` / `bpmRanges` / decades / Camelot | 1 |
| `TrackHit.highlights` + 2-arg ctor | 2 |
| `TrackFacetsResult` maps; ES all BPM ranges; key + years | 3 |
| Drill-sideways genre + key; mustNot hides genre | 3 |
| Search chips bpm/year/rating; highlights `<b>` | 4 |
| Demo switch = `TrackSearch` only; ES class = connect/rebuild | 5 |
| Educational helper kept; two Lucene indexes OK | 5 (untouched helper for analyze/map/index/rewrite/explain overlay) |
| LCD tokens/engine/total, not curl-as-contract | 5 |
| Contract tests source of truth | 3, 4 |
| ES not ready → Lucene Demo | 5 `engine()` fallback |

No placeholders. Types: `NumericRange`, `TrackFacetsResult(genres,bpm,ratings,years,keys)`, `facets(q,filters,mustNots)`.
