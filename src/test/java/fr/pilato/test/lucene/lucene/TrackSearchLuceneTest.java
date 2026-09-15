package fr.pilato.test.lucene.lucene;

import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackSearch;
import fr.pilato.test.lucene.TrackSearchContractTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

class TrackSearchLuceneTest extends TrackSearchContractTest {

    private static TrackSearchLucene index;

    @BeforeAll
    static void rebuild() throws Exception {
        index = new TrackSearchLucene();
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
