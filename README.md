# lucene-search-tracks

In-process [Apache Lucene](https://lucene.apache.org/) demo used by the
**Lucene Bean Search** series (Parts 1–5). Production helpers live under
`src/main`; tests under `src/test` rebuild a Rekordbox track snapshot in RAM
(`ByteBuffersDirectory`), then search, autocomplete, and facet it.

```bash
mvn test
mvn compile exec:java
```

Requires **Java 25**. The NDJSON under `src/main/resources/tracks.ndjson` is a
snapshot of a local Rekordbox library; tests never open SQLCipher. Each test
prints an emoji narrative of the query and the top Lucene hits (with scores).

`mvn compile exec:java` boots a Javalin playground on
[http://localhost:7070](http://localhost:7070): six chapters (analyze, map,
index, search, suggest, facets) with the Java snippet on the left, live
controls in the middle, and Lucene’s view (tokens, posting lists, `Query`,
`Explanation`, histograms) on the right.

| Post           | Test class                |
|----------------|---------------------------|
| Part 1 Mapping | `TrackDocumentMapperTest` |
| Part 2 Index   | `TrackSearchIndexTest`    |
| Part 3 Search  | `TrackSearchTest`         |
| Part 4 Suggest | `TrackSuggestTest`        |
| Part 5 Facets  | `TrackFacetsTest`         |
