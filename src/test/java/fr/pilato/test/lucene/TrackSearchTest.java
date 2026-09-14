package fr.pilato.test.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackSearchTest {

    private static List<Track> corpus;
    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        corpus = TrackDatasetLoader.load();
        index = new TrackSearchIndex();
        index.rebuild(corpus);
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void bob_returns62Hits() throws Exception {
        assertThat(search("Bob", Map.of(), Map.of())).hasSize(62);
    }

    @Test
    void bobClub_returns26Hits() throws Exception {
        List<Track> hits = search("Bob", Map.of("genre", List.of("Club")), Map.of());
        assertThat(hits).hasSize(26);
        assertThat(titles(hits)).contains("Free (Bob Sinclar Remix)");
        assertThat(titles(hits)).doesNotContain("TRIANGLE DES BERMUDES", "Give Me Love");
    }

    @Test
    void bobClubMinusKeys_returns23Hits() throws Exception {
        List<Track> hits = search(
                "Bob",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")));
        assertThat(hits).hasSize(23);
        assertThat(titles(hits)).doesNotContain(
                "Crazy (Bob Sinclar vs. Dimitri Vegas & Like Mike remix)",
                "Free (Bob Sinclar Remix)");
        assertThat(titles(hits)).contains("I Feel For You (Ben Delay Club Mix)");
    }

    @Test
    void lastTokenIsPrefix_butInfixIsNot() throws Exception {
        assertThat(titles(search("bob sincla", Map.of(), Map.of())))
                .anyMatch(t -> t.toLowerCase().contains("sinclar"));
        assertThat(search("bo sinclar", Map.of(), Map.of())).isEmpty();
        assertThat(titles(search("ouse", Map.of(), Map.of())))
                .noneMatch(t -> t.equalsIgnoreCase("House") || t.toLowerCase().contains("house"));
    }

    @Test
    void queryString_marksFilterAndMustNot() {
        Query q = TrackLuceneQueryBuilder.buildStructured(
                "Bob",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")));
        TrackTestLog.luceneQuery(q);
        String printed = q.toString();
        assertThat(printed).contains("#").contains("-");
    }

    private static List<Track> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        Query lucene = TrackLuceneQueryBuilder.buildStructured(q, filters, mustNots);
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            List<TrackHit> hits = TrackLuceneQueryBuilder.searchHits(searcher, lucene, corpus);
            TrackTestLog.search(q, filters, mustNots, hits);
            return hits.stream().map(TrackHit::track).toList();
        }
    }

    private static List<String> titles(List<Track> tracks) {
        return tracks.stream().map(Track::title).toList();
    }
}
