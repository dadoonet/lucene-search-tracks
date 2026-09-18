package fr.pilato.test.lucene.playground;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexTimingTest {

    @Test
    void formatsEngineDocCountAndMillis() {
        assertThat(IndexTiming.indexed("Lucene", 4466, 812))
                .isEqualTo("Lucene indexed 4466 tracks in 812 ms");
        assertThat(IndexTiming.indexed("Elasticsearch", 4466, 1540))
                .isEqualTo("Elasticsearch indexed 4466 tracks in 1540 ms");
    }
}
