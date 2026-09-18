package fr.pilato.test.lucene.playground;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchCurlTest {

    @Test
    void search_includesApiKeyHeaderUrlAndPrettyBody() {
        ElasticsearchSettings settings = new ElasticsearchSettings(
                "http://localhost:9200/", "secret-key", null);
        SearchRequest request = SearchRequest.of(s -> s
                .index("tracks")
                .size(25)
                .query(q -> q.matchAll(m -> m)));
        String curl = ElasticsearchCurl.search(settings, request);
        assertThat(curl)
                .startsWith("curl")
                .contains("-H \"Content-Type: application/json\"")
                .contains("-H \"Authorization: ApiKey secret-key\"")
                .contains("\"http://localhost:9200/tracks/_search?pretty\"")
                .contains("-d '")
                .contains("\"size\"")
                .contains("\"match_all\"")
                .doesNotContain("\"index\"");
    }

    @Test
    void search_usesBasicAuthWhenOnlyPasswordIsSet() {
        ElasticsearchSettings settings = new ElasticsearchSettings(
                "http://127.0.0.1:9200", null, "supersecret");
        SearchRequest request = SearchRequest.of(s -> s
                .index("tracks")
                .query(q -> q.matchAll(m -> m)));
        String curl = ElasticsearchCurl.search(settings, request);
        assertThat(curl)
                .contains("-u \"elastic:supersecret\"")
                .contains("\"http://127.0.0.1:9200/tracks/_search?pretty\"")
                .doesNotContain("Authorization: ApiKey");
    }
}
