package fr.pilato.test.lucene.playground;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;

final class ElasticsearchCurl {

    private static final JacksonJsonpMapper JSONP = new JacksonJsonpMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ElasticsearchCurl() {}

    static String search(ElasticsearchSettings settings, SearchRequest request) {
        String url = ElasticsearchSettings.normalizeUrl(settings == null ? null : settings.url())
                + "tracks/_search?pretty";
        String json = pretty(JsonpUtils.toJsonString(request, JSONP));
        StringBuilder curl = new StringBuilder("curl -sS");
        curl.append(" \\\n  -H \"Content-Type: application/json\"");
        if (settings != null && settings.apiKey() != null) {
            curl.append(" \\\n  -H \"Authorization: ApiKey ").append(settings.apiKey()).append('"');
        } else if (settings != null && settings.password() != null) {
            curl.append(" \\\n  -u \"elastic:").append(settings.password()).append('"');
        }
        curl.append(" \\\n  \"").append(url).append('"');
        curl.append(" \\\n  -d '").append(json.replace("'", "'\\''")).append("'");
        return curl.toString();
    }

    private static String pretty(String json) {
        try {
            JsonNode tree = PRETTY.readTree(json);
            return PRETTY.writeValueAsString(tree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
