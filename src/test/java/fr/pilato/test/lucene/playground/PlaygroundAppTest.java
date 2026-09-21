package fr.pilato.test.lucene.playground;

import io.javalin.testtools.JavalinTest;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundAppTest {

    private static PlaygroundService service;

    @BeforeAll
    static void boot() throws Exception {
        service = PlaygroundService.boot();
    }

    @AfterAll
    static void close() throws Exception {
        service.close();
    }

    @Test
    void analyzeEndpoint_returnsFoldedTokens() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/api/analyze?text=Ultra%20Nat%C3%A9");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("StandardTokenizer")
                    .contains("LowerCaseFilter")
                    .contains("ASCIIFoldingFilter")
                    .contains("\"Ultra\"")
                    .contains("\"nate\"");
        });
    }

    @Test
    void meta_reportsHeapSizeAndBuildTime() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/api/meta");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("\"directory\":\"ByteBuffersDirectory\"")
                    .contains("\"heapSize\":")
                    .contains("\"builtInMs\":")
                    .doesNotContain("corpusSize");
        });
    }

    @Test
    void home_servesPlayground() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("Lucene playground")
                    .contains("lang=\"en\"")
                    .contains("5 Facets")
                    .contains("6 Suggest")
                    .contains("7 Highlighting")
                    .contains("data-chapter=\"demo\">Demo</button>")
                    .contains("id=\"es-settings\"")
                    .contains("fa-solid fa-gear")
                    .contains("id=\"es-dialog\"")
                    .contains("elastic.co/start-local")
                    .contains("--esonly")
                    .contains("ES_LOCAL_API_KEY")
                    .doesNotContain("8 Demo")
                    .contains("data-chapter=\"highlight\"")
                    .contains("Built on Lucene " + Version.LATEST)
                    .contains("href=\"https://david.pilato.fr/\">David Pilato</a>");
        });
    }

    @Test
    void search_returnsExplainTreeKeys() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.post("/api/search", """
                    {"q":"Bob","filters":{},"mustNots":{}}
                    """);
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("\"explainTree\"")
                    .contains("term:title:bob")
                    .contains("prefix:title:bob")
                    .contains("\"dims\"")
                    .contains("\"response\"");
        });
    }

    @Test
    void playgroundJs_wiresExplainHover() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/playground.js");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("bindExplainHover")
                    .contains("data-explain-keys");
        });
    }

    @Test
    void playgroundJs_demoWiresSearchFacetsAndChips() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/playground.js");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("toggleDemoChip")
                    .contains("demo-suggest")
                    .contains("/api/facets")
                    .contains("search.dims")
                    .contains("demo-response")
                    .contains("highlightJson")
                    .contains("playground-lcd-query-ratio")
                    .contains("bindLcdSplit")
                    .doesNotContain("getJson(demoApi(\"/api/facets\")")
                    .contains("mustNots")
                    .contains("fa-music")
                    .contains("fa-tag")
                    .contains("fa-user")
                    .contains("<th>Rating</th>")
                    .contains("<th>Key</th>")
                    .contains("starRow(hit.rating)")
                    .contains("hits in")
                    .contains("search.tookMs")
                    .contains("keyBadge(hit.key)")
                    .contains("camelot-badge")
                    .contains("renderCamelotWheel")
                    .contains("data-camelot-key")
                    .contains("toggleCamelotKey")
                    .contains("annularPath")
                    .contains("get(\"chapter\")")
                    .contains("facetBucketLabel")
                    .contains("hit.highlights")
                    .contains("UnifiedHighlighter")
                    .contains("setZoneZoom(\"deck\")")
                    .contains("demo-backend")
                    .contains("demo-engine-opt")
                    .contains("setDemoBackend")
                    .contains("setDemoReadout")
                    .contains("search.total")
                    .contains("search.query")
                    .contains("demo-query")
                    .contains("/api/elasticsearch")
                    .contains("getElementById(\"es-url\")")
                    .contains("getElementById(\"es-api-key\")");
        });
    }

    @Test
    void elasticsearch_defaultsToLocalhostAndStaysOffline() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/api/elasticsearch");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("\"url\":\"http://localhost:9200/\"")
                    .contains("\"apiKeySet\":false")
                    .contains("\"ready\":false")
                    .doesNotContain("apiKey\":\"");
        });
    }

    @Test
    void elasticsearch_putUnreachableHost_returnsOfflineStatus() {
        try {
            JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
                var response = client.put("/api/elasticsearch", """
                        {"url":"http://127.0.0.1:9/","apiKey":"demo-key"}
                        """);
                assertThat(response.code()).isEqualTo(200);
                String body = response.body().string();
                assertThat(body)
                        .contains("\"url\":\"http://127.0.0.1:9/\"")
                        .contains("\"apiKeySet\":true")
                        .contains("\"ready\":false")
                        .contains("\"error\":")
                        .doesNotContain("demo-key");
            });
        } finally {
            service.disconnectElasticsearch();
        }
    }

    @Test
    void search_elasticsearchBackend_fallsBackToLuceneWhenOffline() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.post("/api/search?backend=elasticsearch", """
                    {"q":"Bob","filters":{},"mustNots":{}}
                    """);
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"total\":62").doesNotContain("curl");
        });
    }

    @Test
    void playgroundJs_suggestWiresSuggesterAndHover() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/playground.js");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("AnalyzingInfixSuggester suggester")
                    .contains("bindSuggestHover")
                    .contains("highlightKey")
                    .contains("matches.get(")
                    .contains("suggest-field")
                    .contains("suggestIcon(hit.field)");
        });
    }

    @Test
    void playgroundJs_hasHighlightChapterAfterSuggest() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/playground.js");
            assertThat(response.code()).isEqualTo(200);
            String js = response.body().string();
            assertThat(js).contains("kicker: \"Part 7 · UnifiedHighlighter\"");
            assertThat(js.indexOf("highlight:")).isGreaterThan(js.indexOf("suggest:"));
            assertThat(js.indexOf("demo:")).isGreaterThan(js.indexOf("highlight:"));
        });
    }

    @Test
    void playgroundCss_demoSplitAndJsonTokens() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/playground.css");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains(".demo-split")
                    .contains("json-key")
                    .contains("row-resize");
        });
    }

    @Test
    void playgroundCss_hasCamelotWheelSlots() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/playground.css");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains(".camelot-badge")
                    .contains(".camelot-1a")
                    .contains(".camelot-12b")
                    .contains(".camelot-unknown")
                    .contains(".camelot-wheel")
                    .contains("opacity: 0.1")
                    .contains("--camelot:")
                    .contains(".stars .fa-regular")
                    .contains("width: 2.6em")
                    .contains("[data-chapter=\"demo\"]")
                    .contains(".demo-cluster")
                    .contains(".es-dialog")
                    .contains(".demo-engine-opt")
                    .contains("margin-left: auto")
                    .contains("border-color: var(--cue)")
                    .contains(".demo-table b")
                    .contains(".hl-field")
                    .contains("#readout b")
                    .contains(".suggest-hit")
                    .contains(".suggest-field");
        });
    }
}
