package fr.pilato.test.lucene;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
}
