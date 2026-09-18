package fr.pilato.test.lucene.playground;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StartScriptTest {

    @Test
    void startSh_sourcesLocalEnvThenRunsPlayground() throws Exception {
        String script = Files.readString(Path.of("start.sh"));
        assertThat(script)
                .contains("ES_LOCAL_URL")
                .contains("ES_LOCAL_PASSWORD")
                .contains(".env")
                .contains("mvn compile exec:java");
    }
}
