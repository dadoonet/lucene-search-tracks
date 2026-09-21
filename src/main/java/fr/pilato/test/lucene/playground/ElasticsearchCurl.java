package fr.pilato.test.lucene.playground;

final class ElasticsearchCurl {

    private ElasticsearchCurl() {}

    static String wrap(ElasticsearchSettings settings, String jsonBody) {
        String url = ElasticsearchSettings.normalizeUrl(settings == null ? null : settings.url())
                + "tracks/_search?pretty";
        String json = jsonBody == null ? "{}" : jsonBody;
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
}
