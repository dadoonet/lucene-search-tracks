package fr.pilato.test.lucene.playground;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackSearchElasticsearchImpl;
import fr.pilato.test.lucene.TrackSuggestion;
import fr.pilato.test.lucene.playground.helpers.TrackAnalyzers;
import fr.pilato.test.lucene.playground.helpers.TrackDocumentMapper;
import fr.pilato.test.lucene.playground.helpers.TrackFacets;
import fr.pilato.test.lucene.playground.helpers.TrackHighlighter;
import org.apache.lucene.facet.range.DoubleRange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static fr.pilato.test.lucene.playground.PlaygroundModels.ElasticsearchStatus;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetBucket;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetDim;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetRewrite;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetsResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchHitView;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchRequest;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SuggestHitView;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SuggestResponse;

final class PlaygroundElasticsearch implements AutoCloseable {

    private static final String INDEX = "tracks";
    private static final String NOT_READY =
            "Elasticsearch is not configured. Open the Demo settings (gear) to set the URL and API key.";

    private final List<Track> corpus;
    private ElasticsearchSettings settings;
    private ElasticsearchClient client;
    private TrackSearchElasticsearchImpl index;
    private boolean ready;
    private String error;
    private int docs;

    PlaygroundElasticsearch(List<Track> corpus, ElasticsearchSettings settings) {
        this.corpus = List.copyOf(corpus);
        this.settings = settings == null ? ElasticsearchSettings.defaults() : settings;
    }

    synchronized ElasticsearchStatus status() {
        return new ElasticsearchStatus(
                settings.url(),
                settings.apiKey() != null,
                ready,
                error,
                docs);
    }

    synchronized boolean ready() {
        return ready;
    }

    synchronized ElasticsearchStatus connect(ElasticsearchSettings next) {
        closeQuietly();
        settings = next == null ? ElasticsearchSettings.defaults() : next;
        ready = false;
        docs = 0;
        error = null;
        try {
            client = openClient(settings);
            client.info();
            index = new TrackSearchElasticsearchImpl(client);
            long start = System.nanoTime();
            index.rebuild(corpus);
            long esMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            docs = corpus.size();
            ready = true;
            System.out.println(IndexTiming.indexed("Elasticsearch", docs, esMs) + " at " + settings.url());
            return status();
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            closeQuietly();
            return status();
        }
    }

    synchronized ElasticsearchStatus connect(String url, String apiKey) {
        return connect(settings.withConnection(url, apiKey));
    }

    synchronized ElasticsearchStatus disconnect() {
        closeQuietly();
        settings = ElasticsearchSettings.fromEnv();
        ready = false;
        docs = 0;
        error = null;
        return status();
    }

    synchronized SearchResponse search(SearchRequest request) throws IOException {
        requireReady();
        String q = request == null || request.q() == null ? "" : request.q();
        Map<String, List<String>> filters = request == null || request.filters() == null
                ? Map.of() : request.filters();
        Map<String, List<String>> mustNots = request == null || request.mustNots() == null
                ? Map.of() : request.mustNots();
        Query esQuery = query(q, filters, mustNots);
        co.elastic.clients.elasticsearch.core.SearchRequest esRequest =
                co.elastic.clients.elasticsearch.core.SearchRequest.of(s -> s
                        .index(INDEX)
                        .size(PlaygroundService.TOP_HITS)
                        .query(esQuery)
                        .highlight(h -> h
                                .preTags("<b>")
                                .postTags("</b>")
                                .numberOfFragments(0)
                                .fields(highlightFields())));
        co.elastic.clients.elasticsearch.core.SearchResponse<Track> response =
                client.search(esRequest, Track.class);
        List<String> tokens = TrackAnalyzers.tokenize(q);
        int total = response.hits().total() == null ? 0 : Math.toIntExact(response.hits().total().value());
        List<SearchHitView> hits = new ArrayList<>();
        for (Hit<Track> hit : response.hits().hits()) {
            Track track = hit.source();
            if (track == null) {
                continue;
            }
            hits.add(new SearchHitView(
                    0,
                    track.id(),
                    track.title(),
                    track.artist(),
                    track.genre(),
                    track.key(),
                    track.bpm(),
                    track.rating(),
                    track.year(),
                    score(hit),
                    null,
                    null,
                    displayedHighlights(track, hit.highlight())));
        }
        return new SearchResponse(q, tokens, ElasticsearchCurl.search(settings, esRequest), total, List.copyOf(hits), 0);
    }

    synchronized SuggestResponse suggest(String prefix) throws Exception {
        requireReady();
        String value = prefix == null ? "" : prefix;
        List<SuggestHitView> hits = new ArrayList<>();
        for (TrackSuggestion suggestion : index.suggest(value)) {
            hits.add(new SuggestHitView(
                    suggestion.text(),
                    suggestion.field(),
                    bTags(suggestion.highlight())));
        }
        return new SuggestResponse(value, List.copyOf(hits));
    }

    synchronized FacetsResponse facets(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws IOException {
        requireReady();
        String queryText = q == null ? "" : q;
        Map<String, List<String>> include = filters == null ? Map.of() : filters;
        Map<String, List<String>> exclude = mustNots == null ? Map.of() : mustNots;
        List<String> genres = include.getOrDefault("genre", List.of()).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        Map<String, List<String>> withoutGenre = new LinkedHashMap<>(include);
        withoutGenre.remove("genre");
        Query base = query(queryText, withoutGenre, exclude);
        Query post = genres.isEmpty() ? null : filterQuery(Map.of("genre", genres));
        Query scoped = post == null ? Query.of(qb -> qb.matchAll(m -> m)) : post;
        co.elastic.clients.elasticsearch.core.SearchResponse<Track> response = client.search(s -> {
                    s.index(INDEX)
                            .size(0)
                            .query(base)
                            .aggregations("genre", a -> a.terms(t -> t.field("genre.raw").size(12)))
                            .aggregations("drill", a -> a
                                    .filter(scoped)
                                    .aggregations("bpm", m -> m.range(r -> {
                                        r.field("bpm");
                                        for (DoubleRange range : TrackFacets.bpmRanges()) {
                                            r.ranges(bucket -> {
                                                bucket.key(range.label).from(range.min);
                                                if (!Double.isInfinite(range.max)) {
                                                    bucket.to(range.max);
                                                }
                                                return bucket;
                                            });
                                        }
                                        return r;
                                    }))
                                    .aggregations("rating", m -> m.terms(t -> t.field("rating").size(10)))
                                    .aggregations("year", m -> m.histogram(h -> h
                                            .field("year")
                                            .interval(10d)
                                            .minDocCount(1)))
                                    .aggregations("key", m -> m.terms(t -> t.field("key").size(24))));
                    if (post != null) {
                        s.postFilter(post);
                    }
                    return s;
                },
                Track.class);
        Map<String, Aggregate> aggs = response.aggregations();
        Map<String, Aggregate> metrics = metrics(aggs.get("drill"));
        return new FacetsResponse(
                queryText,
                describe(queryText, include, exclude),
                !genres.isEmpty(),
                String.join(", ", genres),
                List.of(
                        dim("genre", "🏷️", genreBuckets(aggs.get("genre"))),
                        dim("rating", "⭐", ratingBuckets(metrics.get("rating"))),
                        dim("year", "📅", yearBuckets(metrics.get("year"))),
                        dim("bpm", "⏱", bpmBuckets(metrics.get("bpm"))),
                        dim(TrackFacets.KEY, "🎹", keyBuckets(metrics.get("key")))),
                new FacetRewrite(List.of(), List.of()));
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void requireReady() {
        if (!ready || client == null || index == null) {
            throw new ElasticsearchNotReadyException(error == null || error.isBlank() ? NOT_READY : error);
        }
    }

    private void closeQuietly() {
        ready = false;
        docs = 0;
        if (index != null) {
            try {
                index.close();
            } catch (Exception _) {
                // close
            }
            index = null;
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception _) {
                // close
            }
            client = null;
        }
    }

    private static ElasticsearchClient openClient(ElasticsearchSettings settings) {
        return ElasticsearchClient.of(b -> {
            b.host(settings.url());
            if (settings.apiKey() != null) {
                b.apiKey(settings.apiKey());
            } else if (settings.password() != null) {
                b.usernameAndPassword("elastic", settings.password());
            }
            return b;
        });
    }

    private static Query query(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots) {
        return Query.of(qb -> qb.bool(b -> {
            if (q == null || q.isBlank()) {
                b.must(m -> m.matchAll(ma -> ma));
            } else {
                b.must(m -> m.multiMatch(mm -> mm
                        .query(q)
                        .type(TextQueryType.BoolPrefix)
                        .operator(Operator.And)
                        .fields("title^4", "artist^3", "genre^2", "album^1.5", "label^1", "comment^0.5")));
            }
            addClauses(b, filters, false);
            addClauses(b, mustNots, true);
            return b;
        }));
    }

    private static Query filterQuery(Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        return Query.of(q -> q.bool(b -> {
            addClauses(b, filters, false);
            return b;
        }));
    }

    private static void addClauses(BoolQuery.Builder bool, Map<String, List<String>> clauses, boolean mustNot) {
        if (clauses == null || clauses.isEmpty()) {
            return;
        }
        clauses.forEach((field, values) -> {
            Query clause = fieldFilter(field, values);
            if (clause == null) {
                return;
            }
            if (mustNot) {
                bool.mustNot(clause);
            } else {
                bool.filter(clause);
            }
        });
    }

    private static Query fieldFilter(String field, List<String> values) {
        if (field == null || field.isBlank() || values == null || values.isEmpty()) {
            return null;
        }
        List<Query> parts = new ArrayList<>();
        for (String value : values) {
            Query leaf = fieldQuery(field, value == null ? "" : value);
            if (leaf != null) {
                parts.add(leaf);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return Query.of(q -> q.bool(b -> {
            for (Query part : parts) {
                b.should(part);
            }
            return b.minimumShouldMatch("1");
        }));
    }

    private static Query fieldQuery(String field, String value) {
        return switch (field) {
            case "title", "artist", "genre" -> term(field + ".normalized", value);
            case "key" -> term("key", value);
            case "rating" -> ratingExact(value);
            case "bpm" -> bpmRangeQuery(value);
            case "year" -> yearDecade(value);
            default -> null;
        };
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value.toLowerCase(Locale.ROOT))));
    }

    private static Query ratingExact(String label) {
        try {
            int rating = Integer.parseInt(label.trim());
            return Query.of(q -> q.term(t -> t.field("rating").value(rating)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Query bpmRangeQuery(String label) {
        for (DoubleRange range : TrackFacets.bpmRanges()) {
            if (range.label.equals(label)) {
                return Query.of(q -> q.range(r -> r.number(n -> {
                    n.field("bpm").gte(range.min);
                    if (!Double.isInfinite(range.max)) {
                        n.lt(range.max);
                    }
                    return n;
                })));
            }
        }
        return null;
    }

    private static Query yearDecade(String label) {
        int[] bounds = TrackFacets.decadeBounds(label);
        if (bounds == null) {
            return Query.of(q -> q.matchNone(m -> m));
        }
        return Query.of(q -> q.range(r -> r.number(n -> n
                .field("year")
                .gte((double) bounds[0])
                .lte((double) bounds[1]))));
    }

    private static List<NamedValue<HighlightField>> highlightFields() {
        List<NamedValue<HighlightField>> fields = new ArrayList<>();
        HighlightField whole = HighlightField.of(f -> f.numberOfFragments(0));
        for (String field : TrackHighlighter.FIELDS) {
            fields.add(NamedValue.of(field, whole));
        }
        return fields;
    }

    private static Map<String, String> displayedHighlights(Track track, Map<String, List<String>> highlight) {
        Map<String, String> fields = new LinkedHashMap<>();
        putHighlight(fields, highlight, TrackDocumentMapper.TITLE, track.title());
        putHighlight(fields, highlight, TrackDocumentMapper.ARTIST, track.artist());
        putHighlight(fields, highlight, TrackDocumentMapper.GENRE, track.genre());
        putHighlight(fields, highlight, TrackDocumentMapper.ALBUM, track.album());
        putHighlight(fields, highlight, TrackDocumentMapper.LABEL, track.label());
        putHighlight(fields, highlight, TrackDocumentMapper.COMMENT, track.comment());
        return Map.copyOf(fields);
    }

    private static void putHighlight(
            Map<String, String> fields, Map<String, List<String>> highlight, String name, String fallback) {
        String snippet = null;
        if (highlight != null) {
            List<String> fragments = highlight.get(name);
            if (fragments != null && !fragments.isEmpty()) {
                snippet = bTags(fragments.getFirst());
            }
        }
        if (snippet == null || snippet.isBlank()) {
            snippet = fallback == null ? "" : fallback;
        }
        if (!snippet.isBlank()) {
            fields.put(name, snippet);
        }
    }

    private static String bTags(String html) {
        if (html == null) {
            return null;
        }
        return html.replace("<em>", "<b>").replace("</em>", "</b>")
                .replace("<EM>", "<b>").replace("</EM>", "</b>");
    }

    private static Map<String, Aggregate> metrics(Aggregate drill) {
        if (drill != null && drill.isFilter()) {
            return drill.filter().aggregations();
        }
        return Map.of();
    }

    private static FacetDim dim(String name, String emoji, List<FacetBucket> buckets) {
        return new FacetDim(name, emoji, buckets);
    }

    private static List<FacetBucket> genreBuckets(Aggregate agg) {
        List<FacetBucket> buckets = new ArrayList<>();
        terms(agg).forEach((label, count) -> {
            if (count > 0) {
                buckets.add(new FacetBucket(label, count));
            }
        });
        buckets.sort((a, b) -> Long.compare(b.count(), a.count()));
        if (buckets.size() > 12) {
            return List.copyOf(buckets.subList(0, 12));
        }
        return List.copyOf(buckets);
    }

    private static List<FacetBucket> ratingBuckets(Aggregate agg) {
        Map<String, Long> counts = terms(agg);
        List<FacetBucket> buckets = new ArrayList<>(6);
        for (int stars = 5; stars >= 0; stars--) {
            String label = Integer.toString(stars);
            buckets.add(new FacetBucket(label, countIgnoreCase(counts, label)));
        }
        return List.copyOf(buckets);
    }

    private static List<FacetBucket> yearBuckets(Aggregate agg) {
        Map<String, Long> grouped = new LinkedHashMap<>();
        if (agg != null && agg.isHistogram()) {
            for (var bucket : agg.histogram().buckets().array()) {
                int year = (int) bucket.key();
                String label = TrackFacets.decadeLabel(year);
                if (label == null || bucket.docCount() <= 0) {
                    continue;
                }
                grouped.merge(label, bucket.docCount(), Long::sum);
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new FacetBucket(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(bucket -> decadeStart(bucket.label())))
                .toList();
    }

    private static List<FacetBucket> bpmBuckets(Aggregate agg) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (agg != null && agg.isRange()) {
            for (var bucket : agg.range().buckets().array()) {
                counts.put(bucket.key(), bucket.docCount());
            }
        }
        List<FacetBucket> buckets = new ArrayList<>();
        for (DoubleRange range : TrackFacets.bpmRanges()) {
            long count = counts.getOrDefault(range.label, 0L);
            if (count > 0) {
                buckets.add(new FacetBucket(range.label, count));
            }
        }
        return List.copyOf(buckets);
    }

    private static List<FacetBucket> keyBuckets(Aggregate agg) {
        Map<String, Long> counts = terms(agg);
        List<FacetBucket> buckets = new ArrayList<>(TrackFacets.CAMELOT_CODES.size());
        for (String code : TrackFacets.CAMELOT_CODES) {
            buckets.add(new FacetBucket(code, countIgnoreCase(counts, code)));
        }
        return List.copyOf(buckets);
    }

    private static Map<String, Long> terms(Aggregate agg) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (agg == null) {
            return out;
        }
        if (agg.isSterms()) {
            for (var bucket : agg.sterms().buckets().array()) {
                out.put(bucket.key().stringValue(), bucket.docCount());
            }
        } else if (agg.isLterms()) {
            for (var bucket : agg.lterms().buckets().array()) {
                out.put(Long.toString(bucket.key()), bucket.docCount());
            }
        }
        return out;
    }

    private static long countIgnoreCase(Map<String, Long> counts, String label) {
        Long exact = counts.get(label);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (label.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private static int decadeStart(String label) {
        int[] bounds = TrackFacets.decadeBounds(label);
        return bounds == null ? Integer.MAX_VALUE : bounds[0];
    }

    private static String describe(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots) {
        return "multi_match(" + q + ") filters=" + filters + " must_not=" + mustNots;
    }

    private static float score(Hit<Track> hit) {
        return hit.score() == null ? 0f : hit.score().floatValue();
    }
}
