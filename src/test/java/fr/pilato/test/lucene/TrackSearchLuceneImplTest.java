package fr.pilato.test.lucene;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackSearchLuceneImplTest extends TrackSearchContractTest {

    private static TrackSearchLuceneImpl index;

    @BeforeAll
    static void rebuild() throws Exception {
        index = new TrackSearchLuceneImpl();
        index.rebuild(TrackDatasetLoader.load());
    }

    @AfterAll
    static void close() throws Exception {
        if (index != null) {
            index.close();
        }
    }

    @Override
    protected TrackSearch index() {
        return index;
    }

    @Test
    void printResponse_isEmpty() throws Exception {
        TrackSearchSession session = index().prepareRequest("Bob", Map.of(), Map.of(), 25);
        session.execute();
        assertThat(session.printResponse()).isEmpty();
    }
}
