package fr.pilato.test.lucene.playground;

import io.javalin.testtools.JavalinTest;
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
    void home_servesPlayground() {
        JavalinTest.test(PlaygroundApp.create(service), (server, client) -> {
            var response = client.get("/");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                    .contains("Lucene playground")
                    .contains("lang=\"en\"")
                    .contains("Maven tests remain the contract");
        });
    }
}
