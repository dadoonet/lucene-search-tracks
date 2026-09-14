package fr.pilato.test.lucene;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TrackSuggestTest {

    private static List<Track> corpus;
    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        corpus = TrackDataset.load();
        index = new TrackSearchIndex();
        index.rebuild(corpus);
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void club_returnsGenreAndTitle() throws Exception {
        List<TrackSuggestion> hits = index.suggest("club");
        TrackTestLog.suggest("club", null, hits);
        assertThat(hits)
                .extracting(TrackSuggestion::text, TrackSuggestion::field)
                .contains(tuple("Club House", "genre"))
                .contains(tuple("In Da Club", "title"));
        assertThat(hits)
                .filteredOn(h -> "Club House".equals(h.text()))
                .first()
                .extracting(TrackSuggestion::highlight)
                .asString()
                .containsIgnoringCase("club");
    }

    @Test
    void madonna_returnsArtist() throws Exception {
        List<TrackSuggestion> hits = index.suggest("Madonna");
        TrackTestLog.suggest("Madonna", null, hits);
        assertThat(hits)
                .extracting(TrackSuggestion::text, TrackSuggestion::field)
                .contains(tuple("Madonna", "artist"));
    }

    @Test
    void applyingGenreChip_isFilterNotFreeText() throws Exception {
        var searcher = index.searcher();
        try (var reader = searcher.getIndexReader()) {
            List<TrackHit> withQHits = TrackLuceneQueryBuilder.searchHits(
                    searcher,
                    TrackLuceneQueryBuilder.buildStructured(
                            "club", Map.of("genre", List.of("Club House")), Map.of()),
                    corpus);
            TrackTestLog.search(
                    "club", Map.of("genre", List.of("Club House")), Map.of(), withQHits);
            List<TrackHit> chipOnlyHits = TrackLuceneQueryBuilder.searchHits(
                    searcher,
                    TrackLuceneQueryBuilder.buildStructured(
                            "", Map.of("genre", List.of("Club House")), Map.of()),
                    corpus);
            TrackTestLog.search(
                    "", Map.of("genre", List.of("Club House")), Map.of(), chipOnlyHits);
            assertThat(chipOnlyHits.size()).isGreaterThanOrEqualTo(withQHits.size());
        }
    }

    @Test
    void emptyScope_returnsNothing() throws Exception {
        List<TrackSuggestion> hits = index.suggest("club", List.of());
        TrackTestLog.suggest("club", List.of(), hits);
        assertThat(hits).isEmpty();
    }
}
