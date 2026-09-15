# lucene-search-tracks

Companion repo for the **Lucene Bean Search** series (Parts 1–5) and the
follow-up that repeats the same track search on Elasticsearch.

Production helpers live under `src/main`; tests under `src/test` rebuild a
Rekordbox track snapshot (`tracks.ndjson`) — in a Lucene RAM directory, or in
Elasticsearch via Testcontainers — then search, autocomplete, and facet it.

```bash
mvn test
mvn compile exec:java
```

Requires **Java 25**. Elasticsearch tests also need Docker (image
`docker.elastic.co/elasticsearch/elasticsearch:9.5.2`). The NDJSON is a
snapshot of a local Rekordbox library; tests never open SQLCipher. Each test
prints an emoji narrative of the query and the top hits.

`mvn compile exec:java` boots a Javalin playground on
[http://localhost:7070](http://localhost:7070): analyze, map, index, search,
facets, suggest, highlighting, then a demo tab on the far right. The Java
snippet sits on the left, live controls in the middle, and Lucene’s view
(tokens, posting lists, `Query`, `Explanation`, histograms, highlighted
fields) on the right. Effort notes for the Elasticsearch
side: [`docs/elasticsearch-vs-lucene.md`](docs/elasticsearch-vs-lucene.md).

| Post              | Lucene                    | Elasticsearch                          |
|-------------------|---------------------------|----------------------------------------|
| Part 1 Mapping    | `TrackDocumentMapperTest` | index template in `TrackSearchElasticsearch` |
| Part 2 Index      | `TrackSearchIndexTest`    | `BulkIngester` of `Track` beans        |
| Part 3 Search     | `TrackSearchContractTest` via `TrackSearchLuceneTest` | same contract via `TrackSearchElasticsearchTest` |
| Part 4 Suggest    | same                      | same                                   |
| Part 5 Facets     | same                      | same (aggs + `post_filter`)            |
| Highlighting      | `TrackHighlighterTest`    | —                                      |

`TrackSearch` is the shared API (`rebuild` / `search` / `facets` / `suggest`). Construct `TrackSearchLucene` or `TrackSearchElasticsearch`.
