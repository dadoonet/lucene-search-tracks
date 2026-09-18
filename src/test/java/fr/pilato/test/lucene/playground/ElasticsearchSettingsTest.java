package fr.pilato.test.lucene.playground;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchSettingsTest {

    @Test
    void fromEnv_defaultsUrlAndKeepsCredentialsEmpty() {
        ElasticsearchSettings settings = ElasticsearchSettings.from(Map.of());
        assertThat(settings.url()).isEqualTo("http://localhost:9200/");
        assertThat(settings.apiKey()).isNull();
        assertThat(settings.password()).isNull();
        assertThat(settings.hasCredentials()).isFalse();
    }

    @Test
    void fromEnv_readsStartLocalUrlPasswordAndApiKey() {
        ElasticsearchSettings settings = ElasticsearchSettings.from(Map.of(
                "ES_LOCAL_URL", "http://127.0.0.1:9200",
                "ES_LOCAL_PASSWORD", "supersecret",
                "ES_LOCAL_API_KEY", "encoded-key"));
        assertThat(settings.url()).isEqualTo("http://127.0.0.1:9200/");
        assertThat(settings.password()).isEqualTo("supersecret");
        assertThat(settings.apiKey()).isEqualTo("encoded-key");
        assertThat(settings.hasCredentials()).isTrue();
    }

    @Test
    void withApiKey_keepsPasswordAndNormalizesUrl() {
        ElasticsearchSettings settings = ElasticsearchSettings.from(Map.of(
                        "ES_LOCAL_URL", "http://localhost:9200",
                        "ES_LOCAL_PASSWORD", "supersecret"))
                .withConnection("http://es.local:9200", "pasted-key");
        assertThat(settings.url()).isEqualTo("http://es.local:9200/");
        assertThat(settings.apiKey()).isEqualTo("pasted-key");
        assertThat(settings.password()).isEqualTo("supersecret");
    }

    @Test
    void withConnection_blankApiKeyKeepsPrevious() {
        ElasticsearchSettings settings = ElasticsearchSettings.from(Map.of(
                        "ES_LOCAL_API_KEY", "kept"))
                .withConnection("http://localhost:9200/", "  ");
        assertThat(settings.apiKey()).isEqualTo("kept");
    }
}
