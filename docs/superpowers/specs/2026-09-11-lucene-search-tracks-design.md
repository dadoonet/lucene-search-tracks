# Lucene search tracks — demo design

Companion repo for the [Lucene Bean Search](https://david.pilato.fr/) series
(Parts 1–5). It verifies the posts with the smallest possible Java demo: no UI,
no Diggo, no Elasticsearch. Tests only.

Later copy (out of scope here): same `Track` + `tracks.ndjson`, Elasticsearch
in Testcontainers, new Java API Client — article 7.

## Goals

- Check that the five posts still hold against a real Rekordbox-sized corpus.
- Publish the repo on GitHub and link it from the articles.
- Walk colleagues through “Elasticsearch behind the scene” with readable Lucene.

## Non-goals

- Upsert / delete by id (Part 2 mentions them; this demo only `rebuild`s).
- Web UI, HTTP, settings, logging beyond what tests need.
- Diggo as a Maven dependency of the published project.
- SQLCipher, Rekordbox key, file paths, artwork, uuid, `*.present` fields.
- Elasticsearch / Testcontainers (article 7).

## Project skeleton

Path: `/Users/david/IdeaProjects/blog-tests/lucene-search-tracks`

| Item           | Value                                              |
|----------------|----------------------------------------------------|
| Build          | Maven, one module, no parent POM                   |
| Java           | **25** (`maven.compiler.release`)                  |
| Package        | `fr.pilato.test.lucene`                            |
| groupId        | `fr.pilato.test.lucene`                            |
| artifactId     | `lucene-search-tracks`                             |
| Lucene         | latest stable `core` / `analysis-common` / `facet` / `suggest` (same version) |
| Test           | latest stable JUnit Jupiter, AssertJ, Jackson      |
| Plugins        | latest stable `maven-compiler-plugin`, `maven-surefire-plugin` |
| Production     | **empty** `src/main` — everything lives in `src/test` |

Style: `elasticsearch-java-client-demo` (helpers + tests under `src/test/java`).

```
lucene-search-tracks/
  pom.xml
  README.md
  src/test/java/fr/pilato/test/lucene/
    Track.java
    TrackIndexFields.java
    TrackAnalyzers.java
    TrackDocumentMapper.java
    TrackSearchIndex.java
    TrackLuceneQueryBuilder.java
    TrackDataset.java
    TrackDocumentMapperTest.java
    TrackSearchIndexTest.java
    TrackSearchTest.java
    TrackSuggestTest.java
    TrackFacetsTest.java
  src/test/resources/
    tracks.ndjson
```

Helpers are the blog snippets, not a copy of Diggo (`TrackSearchIndex`,
`DiggoLuceneQueryBuilder`, artwork, duration, added, …).

## Dataset pipeline

```
Rekordbox master.db          (SQLCipher, author's machine, never Git)
        │
        │  One-shot dump using Diggo RekordboxLibrary.tracks()
        │  Same visible-track filter as Diggo
        │  (rb_local_deleted = 0, ServiceID = 0, FolderPath present)
        │
        ▼
src/test/resources/tracks.ndjson     (committed snapshot, ~4 300 lines)
        │
        │  TrackDataset.load()  — Jackson, no JDBC
        │
        ▼
List<Track>  →  mapper / index / search / suggest / facets tests
```

The dump tool depends on `diggo-lib` and stays **out of the published repo**
(shipping it would pull SQLCipher + the Rekordbox key). Tests never open
`master.db`.

### `Track` (1:1 with a JSON line)

```java
public record Track(
        String id,
        String title,
        String artist,
        String genre,
        String key,      // Camelot display, e.g. "4B"
        double bpm,
        int rating,
        int year,
        String album,
        String label,
        String comment
) {}
```

No nested `Artist` / `Genre` records: they would wrap a single name. Missing
Rekordbox values: `null` for strings, `0` for `bpm` / `rating` / `year`.
No file paths, no Rekordbox FKs. `comment` is exported as stored (needed for
Part 3 boost 0.5).

Example line:

```json
{"id":"255465792","title":"Free (Bob Sinclar Remix)","artist":"Ultra Naté","genre":"Club","key":"4B","bpm":128.0,"rating":3,"year":0,"album":null,"label":null,"comment":null}
```

`TrackDataset.load()` reads every line into `List<Track>`. Tests share that list
(typically a `@BeforeAll` rebuild of one in-memory index).

If the current library no longer matches the post screenshots, tests fail on
purpose: fix the dump or the article, do not loosen assertions.

## Lucene mapping

One mapper from day one (all five posts). Analyzer matches index and query
time: `StandardTokenizer` → `LowerCaseFilter` → `ASCIIFoldingFilter`. No
stemming, no stop words, no edge n-grams. NFC may run in the mapper on string
values.

Documents go through a shared `FacetsConfig.build(...)` before `addDocument`.

| Lucene field               | Type                            | Role                                      |
|----------------------------|---------------------------------|-------------------------------------------|
| `id`                       | `StringField` stored            | Join hit → bean                           |
| `title`                    | `TextField`                     | Free text, boost 4.0                      |
| `artist`                   | `TextField`                     | Free text, boost 3.0                      |
| `genre`                    | `TextField`                     | Free text, boost 2.0                      |
| `album`                    | `TextField`                     | Free text, boost 1.5                      |
| `label`                    | `TextField`                     | Free text, boost 1.0                      |
| `comment`                  | `TextField`                     | Free text, boost 0.5                      |
| `genre.raw`                | `StringField`                   | Display / drill-down value                |
| `genre.raw.normalized`     | `StringField`                   | Exact FILTER (`club`)                     |
| `artist.raw`               | `StringField`                   | Part 4 chip display                       |
| `artist.raw.normalized`    | `StringField`                   | Exact FILTER (`madonna`)                  |
| `title.raw`                | `StringField`                   | Part 4 chip display                       |
| `title.raw.normalized`     | `StringField`                   | Exact FILTER when a title suggestion is applied |
| `key.code`                 | `StringField`                   | Exact FILTER / MUST_NOT (`4a`)            |
| `bpm`                      | `DoubleField`                   | Range + `DoubleRangeFacetCounts`          |
| `rating`                   | `IntField`                      | `LongValueFacetCounts`                    |
| `year`                     | `IntField`                      | `LongValueFacetCounts` (decades in tests) |
| `genre` (facet dim)        | `SortedSetDocValuesFacetField`  | Genre histogram                           |

Normalized keyword twins: lowercase + ASCII-fold so `Club` and `club` hit the
same `TermQuery`. Camelot codes similarly (`4A` → `4a`). Do not wildcard
filters: `club` must not match a genre named `Club House`.

`TrackSearchIndex`:

- `ByteBuffersDirectory` + `IndexWriter(TrackAnalyzers.searchAnalyzer())`
- Second `ByteBuffersDirectory` + `AnalyzingInfixSuggester` (same analyzer)
- `rebuild(List<Track>)`: `deleteAll`, add each `FacetsConfig.build(mapper)`,
  `commit`, rebuild suggester from title / artist / genre (deduped, payload =
  field name)
- `searcher()`: NRT `DirectoryReader.open(writer)` — caller closes the reader
- `close()`: suggester + its directory, then writer, then track directory
- One write lock around rebuild
- No `upsert` / `deleteById`

## Query builder (Part 3)

`TrackLuceneQueryBuilder.buildStructured(q, filters, mustNots)`:

- Blank everything → `MatchAllDocsQuery`
- Free text: tokenize `q` with the search analyzer. Each token is a
  `SHOULD` `TermQuery` per field (title 4, artist 3, genre 2, album 1.5,
  label 1, comment 0.5). The **last** token also gets a `PrefixQuery` at
  0.25 × field boost. `minShouldMatch = 1`. Several tokens are AND-ed at
  the outer level (each token is a MUST clause of that SHOULD-group, or
  equivalent Boolean tree as in the post).
- Includes (`filters`): `FILTER` `TermQuery` on the matching `*.raw.normalized`
  (or `key.code`) field. Part 4 chips become `genre=…` / `artist=…` / `title=…`.
- Excludes (`mustNots`): `MUST_NOT` of a `SHOULD` group, `minShouldMatch = 1`
- Only `MUST_NOT` (no `q`, no includes) → add `MUST` `MatchAllDocsQuery`

Resolve hits: stored `id` → `Map<String, Track>`. Walk `scoreDocs` in order.

## Tests (pin the posts)

All tests use the real `tracks.ndjson`. Rebuild once per class when possible.

### `TrackDocumentMapperTest` — Part 1

- Ultra Naté / Free maps to stored `id`, analyzed `title` / `artist`
- ASCII folding: query token `nate` matches `Naté`
- `genre.raw.normalized` = `club`, `key.code` = `4b`
- `bpm` / `rating` numeric fields present

### `TrackSearchIndexTest` — Part 2

- `rebuild(dataset)` → `reader.numDocs() == dataset.size()`
- Searcher sees documents after commit
- `close()` is safe
- Wall time / `ramBytesUsed` may be logged; **do not assert** them

### `TrackSearchTest` — Part 3

| Request                                      | Assertion   |
|----------------------------------------------|-------------|
| `q=Bob`                                      | **62** hits |
| `q=Bob` + `genre=Club`                       | **26**      |
| `q=Bob` + Club + `minus-key=4A,4B`           | **23**      |

Also:

- *Crazy (Bob Sinclar vs. Dimitri Vegas & Like Mike remix)* is a title hit
- artist *Bob Sinclar* is an artist hit
- *Bobo au coeur* matches via `bob*`
- `bob sincla` matches `Sinclar`; `bo sinclar` does not; `ouse` does not find House
- Club ON: *Free (Bob Sinclar Remix)* stays; *TRIANGLE DES BERMUDES* and
  *Give Me Love* drop
- minus 4A/4B: *Crazy* and *Free* drop; *I Feel For You (Ben Delay Club Mix)* (2A Club) stays
- `Query.toString()` contains `FILTER` / `MUST_NOT` markers (`#`, `-`) as in
  the post (smoke, not a full string freeze)

### `TrackSuggestTest` — Part 4

- `lookup("club")`: a `genre` row (*Club House*), a `title` row (*In Da Club*),
  highlight markup present, `payload` is the field name
- `lookup("Madonna")`: `artist` *Madonna* (and infix titles)
- Applying *Club House* searches with `genre=Club House` and **empty** `q`,
  not `q=club` AND genre
- Scoped suggest: with a FILTER corpus, suggestions only keep values that
  appear on those tracks; empty scope → no hits; `scope == null` → full dict

### `TrackFacetsTest` — Part 5

Under `q=Bob` (same Part 3 MUST query, collector `n=1`):

| Bucket              | Count  |
|---------------------|--------|
| genre Club          | **26** |
| BPM 120–130         | **52** |
| rating 5            | **13** |
| year 2020–2029      | **15** |

`DrillSideways`: `FILTER genre=Club` shrinks the BPM histogram; the genre
panel still lists Dance.

Part 3 search hits for `genre=Club` and the Part 5 Club facet are both **26**:
the chip is an exact `genre.raw.normalized` term, same label the SSDV facet
counts. *I Can't Wait* (Club House) is a Bob hit but not a Club hit.

## Error handling

- Missing / unreadable `tracks.ndjson` → test fails at `@BeforeAll` with a
  clear message
- Null strings in mapper → index `""` (Lucene fields reject null)
- Blank `q` + no filters → `MatchAllDocsQuery`, not an empty BooleanQuery

## Article 7 (later, not this spec)

Copy `Track`, `TrackDataset`, `tracks.ndjson`. Replace Lucene helpers with
the Elasticsearch Java API Client + Testcontainers. Same assertions where
the query DSL allows.

## Implementation notes

- TDD: write the failing test class first, then the helper it needs.
- Dump `tracks.ndjson` before search tests that pin counts.
- JDK 25. Latest stable Maven 3.9+. Every library and Maven plugin version
  is the newest **stable** on Maven Central at the moment it is declared
  (no RC / milestone). Do not reuse blog or Diggo version numbers if
  Central has moved on. The four Lucene artefacts share one version.
)
