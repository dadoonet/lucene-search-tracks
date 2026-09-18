package fr.pilato.test.lucene.playground;

import java.util.Map;

public record ElasticsearchSettings(String url, String apiKey, String password) {

    public static final String DEFAULT_URL = "http://localhost:9200/";

    public static ElasticsearchSettings defaults() {
        return new ElasticsearchSettings(DEFAULT_URL, null, null);
    }

    public static ElasticsearchSettings fromEnv() {
        return from(System.getenv());
    }

    public static ElasticsearchSettings from(Map<String, String> env) {
        if (env == null) {
            return defaults();
        }
        String url = firstNonBlank(env.get("ES_LOCAL_URL"), DEFAULT_URL);
        return new ElasticsearchSettings(
                normalizeUrl(url),
                blankToNull(env.get("ES_LOCAL_API_KEY")),
                blankToNull(env.get("ES_LOCAL_PASSWORD")));
    }

    public boolean hasCredentials() {
        return apiKey != null || password != null;
    }

    public ElasticsearchSettings withConnection(String url, String apiKey) {
        String nextKey = blankToNull(apiKey);
        return new ElasticsearchSettings(
                normalizeUrl(firstNonBlank(url, this.url)),
                nextKey == null ? this.apiKey : nextKey,
                password);
    }

    static String normalizeUrl(String url) {
        String value = url == null ? DEFAULT_URL : url.trim();
        if (value.isEmpty()) {
            return DEFAULT_URL;
        }
        return value.endsWith("/") ? value : value + "/";
    }

    private static String firstNonBlank(String value, String fallback) {
        return blankToNull(value) == null ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "ElasticsearchSettings[url=" + url
                + ", apiKey=" + (apiKey == null ? "null" : "***")
                + ", password=" + (password == null ? "null" : "***")
                + "]";
    }

}
