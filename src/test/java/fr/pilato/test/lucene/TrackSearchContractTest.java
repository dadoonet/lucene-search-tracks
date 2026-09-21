package fr.pilato.test.lucene;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public abstract class TrackSearchContractTest {

    protected abstract TrackSearch index();

    @Test
    void search_hitHasEmptyHighlightsByDefault() throws Exception {
        TrackHit hit = index().search("Bob", Map.of(), Map.of()).getFirst();
        assertThat(hit.highlights()).isNotNull();
    }

    @Test
    void printQuery_bobMentionsTheText() throws Exception {
        String printed = index().printQuery("Bob", Map.of(), Map.of());
        assertThat(printed).containsIgnoringCase("bob");
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
    void applyingGenreChip_isFilterNotFreeText() throws Exception {
        List<TrackHit> withQHits = index().search(
                "club", Map.of("genre", List.of("Club House")), Map.of());
        TrackTestLog.search("club", Map.of("genre", List.of("Club House")), Map.of(), withQHits);
        List<TrackHit> chipOnlyHits = index().search(
                "", Map.of("genre", List.of("Club House")), Map.of());
        TrackTestLog.search("", Map.of("genre", List.of("Club House")), Map.of(), chipOnlyHits);
        assertThat(chipOnlyHits.size()).isGreaterThanOrEqualTo(withQHits.size());
    }

    @Test
    void yearJunkChip_matchesNothing() throws Exception {
        assertThat(index().search("", Map.of("year", List.of("0–9")), Map.of())).isEmpty();
    }

    @Test
    void bpmChip_keepsOnlyThatRange() throws Exception {
        List<TrackHit> hits = index().search("Bob", Map.of("bpm", List.of("120 – 130")), Map.of());
        assertThat(hits).isNotEmpty().allMatch(h -> h.track().bpm() >= 120 && h.track().bpm() < 130);
    }

    @Test
    void bob_highlightsTitle() throws Exception {
        assertThat(index().search("Bob", Map.of(), Map.of()))
                .filteredOn(hit -> "Free (Bob Sinclar Remix)".equals(hit.track().title()))
                .isNotEmpty()
                .allSatisfy(hit -> {
                    assertThat(hit.highlights().get("title")).containsIgnoringCase("bob");
                    assertThat(hit.highlights().get("title")).contains("<b>");
                });
    }

    @Test
    void search_highlightsLabelWhenTermMatchesLabel() throws Exception {
        assertThat(index().search("wagram", Map.of(), Map.of()))
                .filteredOn(hit -> hit.highlights().get("label") != null)
                .isNotEmpty()
                .allSatisfy(hit -> {
                    assertThat(hit.highlights().get("label")).containsIgnoringCase("wagram");
                    assertThat(hit.highlights().get("label")).contains("<b>");
                });
    }

    @Test
    void bob_countsGenreBpmRatingYear() throws Exception {
        TrackFacetsResult facets = index().facets("Bob", Map.of());
        assertThat(facets.genres()).containsKey("Club");
        assertThat(facets.genres()).doesNotContainKey("club");
        assertThat(count(facets.genres(), "Club")).isEqualTo(26);
        assertThat(facets.bpm().get("120 – 130")).isEqualTo(52L);
        assertThat(count(facets.ratings(), "5")).isEqualTo(13);
        assertThat(facets.years().get("2020–2029")).isEqualTo(15L);
        assertThat(facets.keys()).isNotEmpty();
        assertThat(facets.keys()).containsKey("4B");
        assertThat(facets.keys()).doesNotContainKey("4b");
        assertThat(facets.bpm().keySet()).contains("120 – 130");
        TrackTestLog.facets("Bob", List.of(
                new TrackTestLog.FacetLine("🏷️", "Club", 26),
                new TrackTestLog.FacetLine("⏱", "120 – 130", 52),
                new TrackTestLog.FacetLine("⭐", "5", 13),
                new TrackTestLog.FacetLine("📅", "2020–2029", facets.years().get("2020–2029"))));
    }

    @Test
    void postFilter_keepsOtherGenres() throws Exception {
        TrackFacetsResult facets = index().facets("Bob", Map.of("genre", List.of("Club")));
        assertThat(count(facets.genres(), "Dance")).isGreaterThan(0);
        assertThat(facets.bpm().getOrDefault("120 – 130", 0L)).isLessThan(52L);
        TrackTestLog.drillSideways(
                "Bob",
                "genre",
                "Club",
                count(facets.genres(), "Dance"),
                facets.bpm().getOrDefault("120 – 130", 0L));
    }

    @Test
    void filteringKey_doesNotShrinkOtherKeyCounts() throws Exception {
        TrackFacetsResult unfiltered = index().facets("Bob", Map.of());
        TrackFacetsResult filtered = index().facets("Bob", Map.of("key", List.of("4B")));
        assertThat(count(filtered.keys(), "4A")).isEqualTo(count(unfiltered.keys(), "4A"));
        assertThat(count(filtered.keys(), "4B")).isEqualTo(count(unfiltered.keys(), "4B"));
    }

    @Test
    void mustNotGenreClub_hidesClubKeepsDance() throws Exception {
        TrackFacetsResult facets = index().facets("Bob", Map.of(), Map.of("genre", List.of("Club")));
        assertThat(count(facets.genres(), "Club")).isZero();
        assertThat(count(facets.genres(), "Dance")).isGreaterThan(0);
    }

    @Test
    void club_returnsGenreAndTitle() throws Exception {
        List<TrackSuggestion> hits = index().suggest("club");
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
    void joeSmo_returnsJoeSmoothFirst() throws Exception {
        List<TrackSuggestion> hits = index().suggest("joe smo");
        TrackTestLog.suggest("joe smo", null, hits);
        assertThat(hits)
                .isNotEmpty()
                .first()
                .extracting(TrackSuggestion::text, TrackSuggestion::field)
                .containsExactly("Joe Smooth", "artist");
    }

    @Test
    void madonna_returnsArtist() throws Exception {
        List<TrackSuggestion> hits = index().suggest("Madonna");
        TrackTestLog.suggest("Madonna", null, hits);
        assertThat(hits)
                .extracting(TrackSuggestion::text, TrackSuggestion::field)
                .contains(tuple("Madonna", "artist"));
    }

    @Test
    void emptyScope_returnsNothing() throws Exception {
        List<TrackSuggestion> hits = index().suggest("club", List.of());
        TrackTestLog.suggest("club", List.of(), hits);
        assertThat(hits).isEmpty();
    }

    private List<Track> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        List<TrackHit> hits = index().search(q, filters, mustNots);
        TrackTestLog.search(q, filters, mustNots, hits);
        return hits.stream().map(TrackHit::track).toList();
    }

    private static List<String> titles(List<Track> tracks) {
        return tracks.stream().map(Track::title).toList();
    }

    private static long count(Map<String, Long> buckets, String label) {
        if (buckets == null || label == null) {
            return 0;
        }
        for (Map.Entry<String, Long> entry : buckets.entrySet()) {
            if (label.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 0;
    }
}
