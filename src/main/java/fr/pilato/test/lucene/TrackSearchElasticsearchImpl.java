package fr.pilato.test.lucene;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._helpers.bulk.BulkIngester;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.util.NamedValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrackSearchElasticsearchImpl implements TrackSearch {

    static final String INDEX = "tracks";
    private static final int SUGGEST_LIMIT = 10;
    private static final Pattern HIGHLIGHT = Pattern.compile("<em>(.*?)</em>", Pattern.CASE_INSENSITIVE);
    private static final JacksonJsonpMapper JSONP = new JacksonJsonpMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final ElasticsearchClient client;

    public TrackSearchElasticsearchImpl(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void rebuild(List<Track> tracks) throws IOException {
        client.indices().putIndexTemplate(t -> t
                .name("tracks")
                .indexPatterns("tracks*")
                .template(te -> te
                        .settings(s -> s.analysis(a -> a
                                .analyzer("track", an -> an.custom(c -> c
                                        .tokenizer("standard")
                                        .filter("lowercase", "asciifolding")))
                                .normalizer("keyword_ci", n -> n.custom(c -> c
                                        .filter("lowercase", "asciifolding")))))
                        .mappings(m -> m
                                .properties("title", textWithRaw())
                                .properties("artist", textWithRaw())
                                .properties("genre", textWithRaw())
                                .properties("album", textWithRaw())
                                .properties("label", textWithRaw())
                                .properties("comment", textWithRaw())
                                .properties("key", p -> p.keyword(k -> k
                                        .normalizer("keyword_ci")
                                        .fields("raw", f -> f.keyword(kw -> kw))))
                                .properties("bpm", p -> p.double_(d -> d))
                                .properties("rating", p -> p.integer(i -> i))
                                .properties("year", p -> p.integer(i -> i)))));
        if (client.indices().exists(e -> e.index(INDEX)).value()) {
            client.indices().delete(d -> d.index(INDEX));
        }
        client.indices().create(c -> c.index(INDEX));
        try (BulkIngester<Void> ingester = BulkIngester.of(b -> b
                .client(client)
                .maxOperations(500)
                .globalSettings(s -> s.index(INDEX)))) {
            for (Track track : tracks) {
                ingester.add(op -> op.index(idx -> idx.id(track.id()).document(track)));
            }
        }
        client.indices().refresh(r -> r.index(INDEX));
    }

    @Override
    public TrackSearchSession prepareRequest(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        return new ElasticsearchSession(q, filters, mustNots, size);
    }

    @Override
    public List<TrackSuggestion> suggest(String prefix) throws IOException {
        return suggest(prefix, null);
    }

    @Override
    public List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws IOException {
        if (prefix == null || prefix.isBlank() || (scope != null && scope.isEmpty())) {
            return List.of();
        }
        SearchResponse<Track> response = client.search(s -> s
                        .index(INDEX)
                        .size(200)
                        .query(q -> q.multiMatch(mm -> mm
                                .query(prefix)
                                .type(TextQueryType.BoolPrefix)
                                .operator(Operator.And)
                                .fields("title", "artist", "genre")))
                        .highlight(h -> h.fields(
                                NamedValue.of("title", HighlightField.of(f -> f.numberOfFragments(0))),
                                NamedValue.of("artist", HighlightField.of(f -> f.numberOfFragments(0))),
                                NamedValue.of("genre", HighlightField.of(f -> f.numberOfFragments(0))))),
                Track.class);
        Map<String, TrackSuggestion> unique = new LinkedHashMap<>();
        for (Hit<Track> hit : response.hits().hits()) {
            Track track = hit.source();
            if (track == null) {
                continue;
            }
            Map<String, List<String>> highlight = hit.highlight();
            addSuggestion(unique, "genre", track.genre(), highlight);
            addSuggestion(unique, "artist", track.artist(), highlight);
            addSuggestion(unique, "title", track.title(), highlight);
        }
        return unique.values().stream()
                .sorted(Comparator
                        .comparingInt((TrackSuggestion s) -> suggestFieldRank(s.field()))
                        .thenComparing(s -> !wholeWordHighlight(s.highlight(), prefix))
                        .thenComparingInt(s -> s.text().length()))
                .limit(SUGGEST_LIMIT)
                .toList();
    }

    @Override
    public void close() {}

    private final class ElasticsearchSession implements TrackSearchSession {
        private final String q;
        private final Map<String, List<String>> filters;
        private final Map<String, List<String>> mustNots;
        private final int size;
        private boolean executed;
        private int totalHits;
        private List<TrackHit> hits;
        private TrackFacetsResult facets;

        private ElasticsearchSession(
                String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size) {
            this.q = q;
            this.filters = filters;
            this.mustNots = mustNots;
            this.size = size;
        }

        @Override
        public String printQuery() {
            return prettyJson(JsonpUtils.toJsonString(facetRequest(q, filters, mustNots), JSONP));
        }

        @Override
        public void execute() throws Exception {
            Query dsl = query(q, filters, mustNots);
            SearchResponse<Track> response = client.search(s -> s
                            .index(INDEX)
                            .size(size)
                            .trackTotalHits(t -> t.enabled(true))
                            .query(dsl)
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
                                            NamedValue.of("comment", HighlightField.of(f -> f)))),
                    Track.class);
            List<TrackHit> ordered = new ArrayList<>();
            for (Hit<Track> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    ordered.add(new TrackHit(hit.source(), score(hit), highlightMap(hit.highlight())));
                }
            }
            this.hits = List.copyOf(ordered);
            this.totalHits = (int) response.hits().total().value();

            SearchResponse<Track> facetResponse = client.search(facetRequest(q, filters, mustNots), Track.class);
            Map<String, Aggregate> aggs = facetResponse.aggregations();
            Map<String, Aggregate> metrics = metrics(aggs.get("drill"));
            this.facets = new TrackFacetsResult(
                    nestedTerms(aggs.get("genre"), "genre"),
                    rangeMap(metrics.get("bpm")),
                    terms(metrics.get("rating")),
                    decades(metrics.get("year")),
                    nestedTerms(aggs.get("key"), "key"));
            this.executed = true;
        }

        @Override
        public int totalHits() {
            requireExecuted();
            return totalHits;
        }

        @Override
        public List<TrackHit> getHits() {
            requireExecuted();
            return hits;
        }

        @Override
        public TrackFacetsResult getFacets() {
            requireExecuted();
            return facets;
        }

        @Override
        public String printResponse() {
            requireExecuted();
            return "";
        }

        private void requireExecuted() {
            if (!executed) {
                throw new IllegalStateException();
            }
        }
    }

    private static SearchRequest facetRequest(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots) {
        Map<String, List<String>> include = filters == null ? Map.of() : filters;
        Map<String, List<String>> exclude = mustNots == null ? Map.of() : mustNots;
        Map<String, List<String>> withoutGenreAndKey = without(include, TrackFacets.GENRE, TrackFacets.KEY);
        return SearchRequest.of(s -> s
                .index(INDEX)
                .size(0)
                .query(query(q, withoutGenreAndKey, exclude))
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
                                .minDocCount(1)))));
    }

    private static String prettyJson(String json) {
        try {
            JsonNode tree = PRETTY.readTree(json);
            return PRETTY.writeValueAsString(tree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            addTerms(b, filters, false);
            addTerms(b, mustNots, true);
            return b;
        }));
    }

    private static Query filterQuery(Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        return Query.of(q -> q.bool(b -> {
            addTerms(b, filters, false);
            return b;
        }));
    }

    private static void addTerms(BoolQuery.Builder bool, Map<String, List<String>> clauses, boolean mustNot) {
        if (clauses == null || clauses.isEmpty()) {
            return;
        }
        clauses.forEach((field, values) -> {
            Query terms = fieldFilter(field, values);
            if (terms == null) {
                return;
            }
            if (mustNot) {
                bool.mustNot(terms);
            } else {
                bool.filter(terms);
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
        for (TrackFacets.NumericRange range : TrackFacets.bpmRanges()) {
            if (range.label().equals(label)) {
                return Query.of(q -> q.range(r -> r.number(n -> {
                    n.field("bpm").gte(range.min());
                    if (!Double.isInfinite(range.max())) {
                        n.lt(range.max());
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

    private static Map<String, List<String>> without(Map<String, List<String>> filters, String... dims) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>(filters);
        for (String dim : dims) {
            out.remove(dim);
        }
        return out;
    }

    private static Query scoped(Map<String, List<String>> filters, String... dims) {
        Map<String, List<String>> subset = new LinkedHashMap<>();
        if (filters != null) {
            for (String dim : dims) {
                List<String> values = filters.get(dim);
                if (values != null && !values.isEmpty()) {
                    subset.put(dim, values);
                }
            }
        }
        Query query = filterQuery(subset);
        return query != null ? query : Query.of(qb -> qb.matchAll(m -> m));
    }

    private static Property textWithRaw() {
        return Property.of(p -> p.text(t -> t
                .analyzer("track")
                .fields("raw", f -> f.keyword(k -> k))
                .fields("normalized", f -> f.keyword(k -> k.normalizer("keyword_ci")))));
    }

    private static Map<String, Aggregate> metrics(Aggregate drill) {
        if (drill != null && drill.isFilter()) {
            return drill.filter().aggregations();
        }
        return Map.of();
    }

    private static int suggestFieldRank(String field) {
        return switch (field) {
            case "genre" -> 0;
            case "title" -> 1;
            case "artist" -> 2;
            default -> 3;
        };
    }

    private static boolean wholeWordHighlight(String highlight, String prefix) {
        if (highlight == null || prefix == null) {
            return false;
        }
        Matcher matcher = HIGHLIGHT.matcher(highlight);
        while (matcher.find()) {
            if (prefix.equalsIgnoreCase(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Long> nestedTerms(Aggregate filter, String name) {
        return terms(metrics(filter).get(name));
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

    private static Map<String, Long> rangeMap(Aggregate agg) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (agg == null || !agg.isRange()) {
            return out;
        }
        for (var bucket : agg.range().buckets().array()) {
            if (bucket.docCount() > 0) {
                out.put(bucket.key(), bucket.docCount());
            }
        }
        return out;
    }

    private static Map<String, Long> decades(Aggregate agg) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (agg == null || !agg.isHistogram()) {
            return out;
        }
        for (var bucket : agg.histogram().buckets().array()) {
            String label = TrackFacets.decadeLabel((int) bucket.key());
            if (label != null && bucket.docCount() > 0) {
                out.merge(label, bucket.docCount(), Long::sum);
            }
        }
        return out;
    }

    private static void addSuggestion(
            Map<String, TrackSuggestion> unique,
            String field,
            String text,
            Map<String, List<String>> highlight) {
        if (text == null || text.isBlank() || highlight == null || !highlight.containsKey(field)) {
            return;
        }
        String key = field + "\0" + text;
        if (unique.containsKey(key)) {
            return;
        }
        List<String> fragments = highlight.get(field);
        String marked = fragments == null || fragments.isEmpty() ? text : fragments.getFirst();
        unique.put(key, new TrackSuggestion(text, field, marked));
    }

    private static float score(Hit<Track> hit) {
        return hit.score() == null ? 0f : hit.score().floatValue();
    }

    private static Map<String, String> highlightMap(Map<String, List<String>> highlight) {
        if (highlight == null || highlight.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        highlight.forEach((field, fragments) -> {
            if (fragments != null && !fragments.isEmpty()) {
                String snippet = fragments.getFirst();
                if (snippet != null && !snippet.isBlank()) {
                    out.put(field, snippet);
                }
            }
        });
        return out;
    }
}
