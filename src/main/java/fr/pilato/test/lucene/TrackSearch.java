package fr.pilato.test.lucene;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Engine-neutral search over a track catalog (Lucene or Elasticsearch).
 *
 * <p>The interesting path is a {@link TrackSearchSession}:
 * {@link #prepareRequest prepare} → {@link TrackSearchSession#printQuery() print}
 * → {@link TrackSearchSession#execute() execute} → hits / total / facets.
 * One {@code execute()} fills all three so the Demo LCD can show the same
 * request that produced the results.
 *
 * <p>{@link #search}, {@link #facets} and {@link #printQuery(String, Map, Map)}
 * are convenience wrappers around a session. Prefer {@code prepareRequest}
 * when you need both hits and facets, or a printable query/response.
 *
 * <h2>Filters</h2>
 * Keys are facet dimensions ({@code genre}, {@code key}, {@code bpm},
 * {@code rating}, {@code year}). Include maps drill in; {@code mustNots} hide
 * matching docs. Genre and key includes are <em>drill-sideways</em>: they
 * filter hits and the <em>other</em> facet maps, but they do not shrink their
 * own map (selecting Club still lists Dance). BPM / rating / year filters
 * are not sideways.
 *
 * @see TrackSearchLuceneImpl
 * @see TrackSearchElasticsearchImpl
 */
public interface TrackSearch extends AutoCloseable {

    /** Replace the indexed catalog. Suggest dictionaries are rebuilt too. */
    void rebuild(List<Track> tracks) throws Exception;

    /**
     * Build a session for {@code q} without talking to the engine yet.
     *
     * @param q        free-text (analyzed). Blank means match-all, still
     *                 subject to {@code filters} / {@code mustNots}.
     * @param filters  include chips. {@code null} is empty.
     * @param mustNots exclude chips. {@code null} is empty.
     * @param size     how many hits to return. {@code 0} is aggregations-only
     *                 (Elasticsearch {@code size: 0}; Lucene still collects
     *                 facets and reports {@link TrackSearchSession#totalHits()}).
     *                 Must not be negative.
     */
    TrackSearchSession prepareRequest(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size);

    /**
     * All matching hits, capped at 10&nbsp;000, with highlights on that page.
     * Use {@link #prepareRequest} with a smaller {@code size} for the Demo table.
     */
    default List<TrackHit> search(
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots) throws Exception {
        TrackSearchSession session = prepareRequest(q, filters, mustNots, 10_000);
        session.execute();
        return session.getHits();
    }

    /**
     * Facet maps only. Runs a session with {@code size = 0} so Elasticsearch
     * does not fetch hits. Counts follow the drill-sideways rules above.
     */
    default TrackFacetsResult facets(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        TrackSearchSession session = prepareRequest(q, filters, mustNots, 0);
        session.execute();
        return session.getFacets();
    }

    /** {@link #facets(String, Map, Map)} with no excludes. */
    default TrackFacetsResult facets(String q, Map<String, List<String>> postFilters) throws Exception {
        return facets(q, postFilters == null ? Map.of() : postFilters, Map.of());
    }

    /**
     * Engine-native rendering of the request {@link #facets} would run
     * ({@code size = 0}, no execute).
     * Lucene: {@code Query#toString()}. Elasticsearch: pretty JSON body.
     */
    default String printQuery(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        return prepareRequest(q, filters, mustNots, 0).printQuery();
    }

    /** Prefix suggestions across title, artist, and genre. */
    List<TrackSuggestion> suggest(String prefix) throws Exception;

    /**
     * Prefix suggestions restricted to {@code scope} (the current result set).
     * Empty prefix or empty scope → no suggestions.
     */
    List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws Exception;
}
