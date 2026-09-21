package fr.pilato.test.lucene.playground;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchCurlTest {

    @Test
    void wrap_usesApiKeyAndPrettySearchUrl() {
        var settings = new ElasticsearchSettings("http://localhost:9200/", "secret-key", null);
        String curl = ElasticsearchCurl.wrap(settings, "{ \"size\": 0 }");
        assertThat(curl)
                .contains("curl -sS")
                .contains("Authorization: ApiKey secret-key")
                .contains("\"http://localhost:9200/tracks/_search?pretty\"")
                .contains("{ \"size\": 0 }");
    }

    @Test
    void wrap_fallsBackToPassword() {
        var settings = new ElasticsearchSettings("http://127.0.0.1:9200", null, "changeme");
        String curl = ElasticsearchCurl.wrap(settings, "{}");
        assertThat(curl)
                .contains("-u \"elastic:changeme\"")
                .contains("\"http://127.0.0.1:9200/tracks/_search?pretty\"");
    }
}
