package fr.pilato.test.lucene;

import org.apache.lucene.store.AlreadyClosedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void session_failedRerun_keepsPreviousCompleteResults() throws Exception {
        TrackSearchLuceneImpl engine = new TrackSearchLuceneImpl();
        engine.rebuild(TrackDatasetLoader.load());
        TrackSearchSession session = engine.prepareRequest("Bob", Map.of(), Map.of(), 25);
        session.execute();
        List<TrackHit> previousHits = session.getHits();
        int previousTotal = session.totalHits();
        TrackFacetsResult previousFacets = session.getFacets();
        engine.close();
        assertThatThrownBy(session::execute).isInstanceOf(AlreadyClosedException.class);
        assertThat(session.getHits()).isSameAs(previousHits);
        assertThat(session.totalHits()).isEqualTo(previousTotal);
        assertThat(session.getFacets()).isSameAs(previousFacets);
    }
}
