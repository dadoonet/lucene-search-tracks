package fr.pilato.test.lucene;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

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
}
