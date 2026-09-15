# Elasticsearch vs Lucene — journal d’effort

Même `Track` + `tracks.ndjson`. Contrat `TrackSearch` ; le volume de code des **implémentations** reste le contraste.

Lucene : `TrackSearchLucene` (ex-`TrackSearchIndex` + query/facets) + analyzers / mapper / query builder / `TrackFacets`.
Elasticsearch : `TrackSearchElasticsearch` (ex-`TrackElasticsearchIndex`).
Assertions search / facets / suggest : `TrackSearchContractTest`, joué par `TrackSearchLuceneTest` et `TrackSearchElasticsearchTest`.

## 1. Ajout d’ES au POM

**Fichiers :** `pom.xml` (~20 lignes : 2 properties + 3 deps).

- `co.elastic.clients:elasticsearch-java:9.5.2` (aligné sur l’image Docker, pas 9.5.3)
- `org.testcontainers:testcontainers-elasticsearch:2.0.5`
- `org.testcontainers:testcontainers-junit-jupiter:2.0.5`

Pourquoi plus court : une coordonnée Maven remplace lucene-core / analysis-common / facet / suggest.

## 2. Instanciation du client Java ES

**Fichiers :** `TrackSearchElasticsearchTest` (~6 lignes).

```java
ElasticsearchClient.of(b -> b
    .host("https://" + elasticsearch.getHttpHostAddress())
    .usernameAndPassword("elastic", ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD)
    .sslContext(elasticsearch.createSslContextFromCa()));
```

Testcontainers 2.0.5 n’a pas `getPassword()` : le mot de passe par défaut du module est la constante `ELASTICSEARCH_DEFAULT_PASSWORD`. HTTPS + CA du container, pas de `RestClient` à câbler.

Pourquoi plus court : pas d’`IndexWriter` / `ByteBuffersDirectory` / `Analyzer` à construire à la main.

## 3. Création d’un index avec son template

**Fichiers :** `TrackSearchElasticsearch.rebuild` — `putIndexTemplate` + `create` (~25 lignes).

Analyzer `track` = standard + lowercase + asciifolding ; normalizer `keyword_ci` ; mapping text + `.raw` keyword.

Pourquoi plus court : plus de `Document` / `TextField` / `StringField` / `DoubleField` / `FacetsConfig.build`.

## 4. BulkIngester des beans Track

**Fichiers :** même `rebuild` (~10 lignes).

```java
ingester.add(op -> op.index(idx -> idx.id(track.id()).document(track)));
```

Puis `refresh`.

Pourquoi plus court : le bean part tel quel, pas de `TrackDocumentMapper.toDocument`.

## 5. Search

**Fichiers :** `search(...)` (~20 lignes) ; assertions dans `TrackElasticsearchTest`.

`multi_match` `bool_prefix` + `operator: and` sur `title^4` … `comment^0.5` ; `term` sur `*.raw` / `key` ; `must_not` sur `key`.

Cibles Lucene : q=Bob → 62 ; +genre=Club → 26 ; +minus 4A/4B → 23 ; `bob sincla` match ; `bo sinclar` vide ; `ouse` ne match pas House.

Pourquoi plus court : plus de `BooleanQuery` / `PrefixQuery` / `BoostQuery` / `MatchAllDocsQuery` assemblés champ par champ.

## 6. Search avec facets (aggs)

**Fichiers :** `facets(...)` (~25 lignes + petits extracteurs de buckets).

`terms` genre, `range` BPM 120–130, `terms` rating, `range` year 2020–2029. Drill-sideways = query + `post_filter` (hits) ; l’agg `genre` reste sur la query (Dance reste visible) ; BPM/rating/year passent dans une agg `filter` (plus de sous-classe `DrillSideways`).

Cibles sous q=Bob : Club 26, BPM 120–130 = 52, rating 5 = 13, 2020–2029 = 15.

Pourquoi plus court : plus de `FacetsCollector` / `SortedSetDocValuesFacetCounts` / `DoubleRangeFacetCounts` / sous-classe `DrillSideways`.

## 7. Autocomplete

**Fichiers :** `suggest(...)` (~40 lignes).

Même `multi_match` `bool_prefix` sur title/artist/genre + `highlight`, dédupe en `TrackSuggestion`. Scope vide → rien.

Pourquoi plus court : plus d’`AnalyzingInfixSuggester` / `InputIterator` / second `Directory`.
