# Music track search demo project for Lucene

Companion repo for the [Lucene Bean Search series](https://david.pilato.fr/series/lucene-bean-search/) 
and the follow-up that repeats the same track search on Elasticsearch.

Production helpers live under `src/main`; tests under `src/test` rebuild a
Rekordbox track snapshot (`tracks.ndjson`) — in a Lucene RAM directory, or in
Elasticsearch via Testcontainers — then search, autocomplete, and facet it.

## The Lucene Playground

A full demo can be run locally with:

```bash
./start.sh
```

Requires **Java 25**. Elasticsearch tests also need Docker (image
`docker.elastic.co/elasticsearch/elasticsearch:9.5.4`). The NDJSON is a
snapshot of a local Rekordbox library; tests never open SQLCipher. Each test
prints an emoji narrative of the query and the top hits.

`mvn compile exec:java` boots a Javalin playground on
[http://localhost:7171](http://localhost:7171): analyze, map, index, search,
facets, suggest, highlighting, then a demo tab on the far right. The Java
snippet sits on the left, live controls in the middle, and Lucene’s view
(tokens, posting lists, `Query`, `Explanation`, histograms, highlighted
fields) on the right.

## With Elasticsearch

Start a local cluster with [start-local](https://github.com/elastic/start-local):

```bash
curl -fsSL https://elastic.co/start-local | sh -s -- --esonly
cat elastic-start-local/.env | grep ES_LOCAL_API_KEY
./start.sh
```

The Demo tab switches between the same Lucene and Elasticsearch `TrackSearch`
implementations the contract tests use. Click the gear next to **Demo** to
set the cluster URL (default `http://localhost:9200/`) and API key, then
**Save and index** to rebuild the `tracks` index. If Elasticsearch is not
ready, the Demo stays on Lucene. 

`./start.sh` sources a local `.env` (or `elastic-start-local/.env`) and exports `ES_LOCAL_URL` /
`ES_LOCAL_API_KEY` before `mvn compile exec:java`. When those credentials exist
at boot, the playground indexes into Elasticsearch automatically.

