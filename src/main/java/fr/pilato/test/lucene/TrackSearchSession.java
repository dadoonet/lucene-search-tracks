package fr.pilato.test.lucene;

import java.util.List;

/**
 * One prepared search: printable request, then one execute, then results.
 *
 * <pre>{@code
 * TrackSearchSession s = index.prepareRequest("Bob", filters, mustNots, 25);
 * s.printQuery();   // valid before execute
 * s.execute();      // one Lucene pass, or one Elasticsearch _search
 * s.totalHits();    // 62 for "Bob" even when size is 25
 * s.getHits();      // at most {@code size} tracks
 * s.getFacets();
 * s.printResponse(); // Elasticsearch JSON; empty on Lucene
 * }</pre>
 *
 * <p>{@code execute()} may be called again; a failed re-run keeps the previous
 * complete snapshot. Reading hits / facets / total / response before the first
 * successful execute throws {@link IllegalStateException}.
 */
public interface TrackSearchSession {

    /**
     * The request this session would send. Lucene: {@code Query#toString()} of
     * the hits query (all filters, including genre/key). Elasticsearch: pretty
     * JSON of the {@code _search} body ({@code size}, query, {@code post_filter},
     * aggregations, highlight when {@code size > 0}).
     */
    String printQuery();

    /**
     * Run the engine once and store hits, total, facets, and (on Elasticsearch)
     * the JSON response. Safe to call more than once.
     */
    void execute() throws Exception;

    /** Full match count, not {@code min(size, matches)}. */
    int totalHits();

    /** Score-ordered page of at most {@code size} hits. Empty when {@code size} is 0. */
    List<TrackHit> getHits();

    /** Drill-sideways facet maps from the same {@link #execute()}. */
    TrackFacetsResult getFacets();

    /**
     * Pretty JSON of the Elasticsearch response after execute. Lucene has no
     * equivalent document: this is always {@code ""}.
     */
    String printResponse();
}
