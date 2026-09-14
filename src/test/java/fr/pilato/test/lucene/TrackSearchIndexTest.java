package fr.pilato.test.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackSearchIndexTest {

    @Test
    void rebuild_indexesEveryTrackInRam() throws Exception {
        List<Track> tracks = TrackDatasetLoader.load();
        try (TrackSearchIndex index = new TrackSearchIndex()) {
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
