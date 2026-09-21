package fr.pilato.test.lucene;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TrackSearchElasticsearchImplTest extends TrackSearchContractTest {

    private static final String IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.5.4";

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(IMAGE);

    private static ElasticsearchClient client;
    private static TrackSearchElasticsearchImpl index;

    @BeforeAll
    static void rebuild() throws Exception {
        client = ElasticsearchClient.of(b -> b
                .host("https://" + elasticsearch.getHttpHostAddress())
                .usernameAndPassword("elastic", ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD)
                .sslContext(elasticsearch.createSslContextFromCa()));
        index = new TrackSearchElasticsearchImpl(client);
        index.rebuild(TrackDatasetLoader.load());
    }

    @AfterAll
    static void close() throws Exception {
        if (index != null) {
            index.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected TrackSearch index() {
        return index;
    }

    @Test
    void printQuery_includesAggregationsAndBpmRanges() throws Exception {
        String json = index().printQuery("Bob", Map.of(), Map.of());
        assertThat(json)
                .contains("aggregations")
                .contains("120 – 130")
                .contains("multi_match");
    }

    @Test
    void printQuery_size25_includesAggregations() throws Exception {
        TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
        String json = session.printQuery();
        JsonNode tree = new ObjectMapper().readTree(json);
        assertThat(tree.path("size").intValue()).isEqualTo(25);
        assertThat(json)
                .contains("\"size\"")
                .contains("aggregations")
                .contains("120 – 130")
                .contains("multi_match")
                .contains("highlight");
    }

    @Test
    void printQuery_wrapperUsesSize0() throws Exception {
        JsonNode tree = new ObjectMapper().readTree(index().printQuery("Bob", Map.of(), Map.of()));
        assertThat(tree.path("size").intValue()).isEqualTo(0);
    }

    @Test
    void printQuery_genreFilter_usesPostFilter() throws Exception {
        TrackSearchSession session = index().prepareRequest(
                "Bob", Map.of("genre", List.of("Club")), Map.of(), 25);
        assertThat(session.printQuery()).contains("post_filter");
    }

    @Test
    void printResponse_afterExecute_hasHitsAndAggregations() throws Exception {
        TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
        session.execute();
        assertThat(session.printResponse())
                .contains("\"hits\"")
                .contains("aggregations")
                .contains("120 – 130");
        assertThat(session.getHits()).hasSize(25);
        assertThat(session.totalHits()).isEqualTo(62);
    }

    @Test
    void session_failedRerunAfterHits_keepsPreviousCompleteResults() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        ElasticsearchClient failing = new ElasticsearchClient(client._transport()) {
            @Override
            public <TDocument> SearchResponse<TDocument> search(
                    SearchRequest request, Class<TDocument> tDocumentClass) throws IOException {
                if (searches.incrementAndGet() == 2) {
                    throw new IOException("search request failed");
                }
                return super.search(request, tDocumentClass);
            }
        };
        TrackSearch engine = new TrackSearchElasticsearchImpl(failing);
        TrackSearchSession session = engine.prepareRequest("Bob", Map.of(), Map.of(), 25);
        session.execute();
        List<TrackHit> previousHits = session.getHits();
        int previousTotal = session.totalHits();
        TrackFacetsResult previousFacets = session.getFacets();
        assertThatThrownBy(session::execute)
                .isInstanceOf(IOException.class)
                .hasMessage("search request failed");
        assertThat(session.getHits()).isSameAs(previousHits);
        assertThat(session.totalHits()).isEqualTo(previousTotal);
        assertThat(session.getFacets()).isSameAs(previousFacets);
        assertThat(session.getHits()).hasSize(25);
        assertThat(session.totalHits()).isEqualTo(62);
    }
}
