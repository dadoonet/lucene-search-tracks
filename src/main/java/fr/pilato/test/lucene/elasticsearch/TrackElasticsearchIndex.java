package fr.pilato.test.lucene.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._helpers.bulk.BulkIngester;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackHit;
import fr.pilato.test.lucene.TrackSuggestion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrackElasticsearchIndex implements AutoCloseable {

    static final String INDEX = "tracks";
    private static final int SUGGEST_LIMIT = 10;
    private static final Pattern HIGHLIGHT = Pattern.compile("<em>(.*?)</em>", Pattern.CASE_INSENSITIVE);

    private final ElasticsearchClient client;

    public TrackElasticsearchIndex(ElasticsearchClient client) {
        this.client = client;
    }

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
                                .properties("key", p -> p.keyword(k -> k.normalizer("keyword_ci")))
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

    public List<TrackHit> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws IOException {
        SearchResponse<Track> response = client.search(s -> s
                        .index(INDEX)
                        .size(10_000)
                        .query(query(q, filters, mustNots)),
                Track.class);
        List<TrackHit> hits = new ArrayList<>();
        for (Hit<Track> hit : response.hits().hits()) {
            if (hit.source() != null) {
                hits.add(new TrackHit(hit.source(), score(hit)));
            }
        }
        return List.copyOf(hits);
    }

    public Facets facets(String q, Map<String, List<String>> postFilters) throws IOException {
        Query post = filterQuery(postFilters);
        Query scoped = post != null ? post : Query.of(qb -> qb.matchAll(m -> m));
        SearchResponse<Track> response = client.search(s -> {
                    s.index(INDEX)
                            .size(0)
                            .query(query(q, Map.of(), Map.of()))
                            .aggregations("genre", a -> a.terms(t -> t.field("genre.raw").size(50)))
                            .aggregations("drill", a -> a
                                    .filter(scoped)
                                    .aggregations("bpm", m -> m.range(r -> r
                                            .field("bpm")
                                            .ranges(rg -> rg.key("120 – 130").from(120d).to(130d))))
                                    .aggregations("rating", m -> m.terms(t -> t.field("rating").size(10)))
                                    .aggregations("year", m -> m.range(r -> r
                                            .field("year")
                                            .ranges(rg -> rg.key("2020–2029").from(2020d).to(2030d)))));
                    if (post != null) {
                        s.postFilter(post);
                    }
                    return s;
                },
                Track.class);
        Map<String, Aggregate> aggs = response.aggregations();
        Map<String, Aggregate> metrics = metrics(aggs.get("drill"));
        return new Facets(
                terms(aggs.get("genre")),
                rangeCount(metrics.get("bpm"), "120 – 130"),
                terms(metrics.get("rating")),
                rangeCount(metrics.get("year"), "2020–2029"));
    }

    public List<TrackSuggestion> suggest(String prefix) throws IOException {
        return suggest(prefix, null);
    }

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

    public record Facets(
            Map<String, Long> genres,
            long bpm120to130,
            Map<String, Long> ratings,
            long year2020s
    ) {}

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
            if (values == null || values.isEmpty()) {
                return;
            }
            Query terms = values.size() == 1
                    ? term(field, values.getFirst())
                    : Query.of(q -> q.bool(b -> {
                        for (String value : values) {
                            b.should(term(field, value));
                        }
                        return b.minimumShouldMatch("1");
                    }));
            if (mustNot) {
                bool.mustNot(terms);
            } else {
                bool.filter(terms);
            }
        });
    }

    private static Query term(String field, String value) {
        String esField = "key".equals(field) ? "key" : field + ".raw";
        return Query.of(q -> q.term(t -> t.field(esField).value(value.toLowerCase(Locale.ROOT))));
    }

    private static Property textWithRaw() {
        return Property.of(p -> p.text(t -> t
                .analyzer("track")
                .fields("raw", f -> f.keyword(k -> k.normalizer("keyword_ci")))));
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

    private static long rangeCount(Aggregate agg, String key) {
        if (agg == null || !agg.isRange()) {
            return 0;
        }
        for (var bucket : agg.range().buckets().array()) {
            if (key.equals(bucket.key())) {
                return bucket.docCount();
            }
        }
        return 0;
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
}
