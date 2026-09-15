package fr.pilato.test.lucene;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface TrackSearch extends AutoCloseable {

    void rebuild(List<Track> tracks) throws Exception;

    List<TrackHit> search(
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots) throws Exception;

    TrackFacetsResult facets(String q, Map<String, List<String>> postFilters) throws Exception;

    List<TrackSuggestion> suggest(String prefix) throws Exception;

    List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws Exception;
}
