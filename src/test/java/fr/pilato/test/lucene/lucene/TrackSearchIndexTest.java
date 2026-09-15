package fr.pilato.test.lucene.lucene;

import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackTestLog;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackSearchIndexTest {

    @Test
    void rebuild_indexesEveryTrackInRam() throws Exception {
        List<Track> tracks = TrackDatasetLoader.load();
        try (TrackSearchLucene index = new TrackSearchLucene()) {
            index.rebuild(tracks);
            TrackTestLog.indexRebuilt(tracks.size(), index.numDocs());
            assertThat(index.numDocs()).isEqualTo(tracks.size());
            IndexSearcher searcher = index.searcher();
            try (IndexReader reader = searcher.getIndexReader()) {
                assertThat(reader.numDocs()).isEqualTo(tracks.size());
            }
        }
    }
}
