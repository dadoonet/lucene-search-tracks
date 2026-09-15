package fr.pilato.test.lucene.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackHit;
import fr.pilato.test.lucene.TrackSuggestion;
import fr.pilato.test.lucene.TrackTestLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Testcontainers
class TrackElasticsearchTest {

    private static final String IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.5.2";

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(IMAGE);

    private static ElasticsearchClient client;
    private static TrackElasticsearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        client = ElasticsearchClient.of(b -> b
                .host("https://" + elasticsearch.getHttpHostAddress())
                .usernameAndPassword("elastic", ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD)
                .sslContext(elasticsearch.createSslContextFromCa()));
        index = new TrackElasticsearchIndex(client);
        index.rebuild(TrackDatasetLoader.load());
    }

    @AfterAll
    static void close() throws Exception {
        if (client != null) {
            client.close();
        }
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
    void bob_countsGenreBpmRatingYear() throws Exception {
        TrackElasticsearchIndex.Facets facets = index.facets("Bob", Map.of());
        assertThat(count(facets.genres(), "Club")).isEqualTo(26);
        assertThat(facets.bpm120to130()).isEqualTo(52);
        assertThat(count(facets.ratings(), "5")).isEqualTo(13);
        assertThat(facets.year2020s()).isEqualTo(15);
        TrackTestLog.facets("Bob", List.of(
                new TrackTestLog.FacetLine("🏷️", "Club", 26),
                new TrackTestLog.FacetLine("⏱", "120 – 130", 52),
                new TrackTestLog.FacetLine("⭐", "5", 13),
                new TrackTestLog.FacetLine("📅", "2020–2029", facets.year2020s())));
    }

    @Test
    void postFilter_keepsOtherGenres() throws Exception {
        TrackElasticsearchIndex.Facets facets = index.facets("Bob", Map.of("genre", List.of("Club")));
        assertThat(count(facets.genres(), "Dance")).isGreaterThan(0);
        assertThat(facets.bpm120to130()).isLessThan(52);
        TrackTestLog.drillSideways(
                "Bob",
                "genre",
                "Club",
                count(facets.genres(), "Dance"),
                facets.bpm120to130());
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
    void emptyScope_returnsNothing() throws Exception {
        List<TrackSuggestion> hits = index.suggest("club", List.of());
        TrackTestLog.suggest("club", List.of(), hits);
        assertThat(hits).isEmpty();
    }

    private static List<Track> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        List<TrackHit> hits = index.search(q, filters, mustNots);
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
