# lucene-search-tracks

In-process [Apache Lucene](https://lucene.apache.org/) demo used by the
**Lucene Bean Search** series (Parts 1–5). Tests only: a Rekordbox track
snapshot is indexed in RAM (`ByteBuffersDirectory`), then searched,
autocompleted, and faceted.

```bash
mvn test
```

Requires **Java 25**. The NDJSON under `src/test/resources/tracks.ndjson` is a
snapshot of a local Rekordbox library; tests never open SQLCipher.

| Post           | Test class                |
|----------------|---------------------------|
| Part 1 Mapping | `TrackDocumentMapperTest` |
| Part 2 Index   | `TrackSearchIndexTest`    |
| Part 3 Search  | `TrackSearchTest`         |
| Part 4 Suggest | `TrackSuggestTest`        |
| Part 5 Facets  | `TrackFacetsTest`         |
