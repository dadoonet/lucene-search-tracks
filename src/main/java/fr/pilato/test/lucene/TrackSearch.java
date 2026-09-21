package fr.pilato.test.lucene;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface TrackSearch extends AutoCloseable {

    void rebuild(List<Track> tracks) throws Exception;

    TrackSearchSession prepareRequest(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, int size);

    default List<TrackHit> search(
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots) throws Exception {
        TrackSearchSession session = prepareRequest(q, filters, mustNots, 10_000);
        session.execute();
        return session.getHits();
    }

    default TrackFacetsResult facets(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        TrackSearchSession session = prepareRequest(q, filters, mustNots, 10_000);
        session.execute();
        return session.getFacets();
    }

    default TrackFacetsResult facets(String q, Map<String, List<String>> postFilters) throws Exception {
        return facets(q, postFilters == null ? Map.of() : postFilters, Map.of());
    }

    /**
     * Engine-native rendering of the search that {@link #search} / {@link #facets} would run.
     * Lucene: {@code Query#toString()}. Elasticsearch: pretty JSON body (query + aggregations).
     */
    default String printQuery(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        return prepareRequest(q, filters, mustNots, 10_000).printQuery();
    }

    List<TrackSuggestion> suggest(String prefix) throws Exception;

    List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws Exception;
}
