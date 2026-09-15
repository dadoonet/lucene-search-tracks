package fr.pilato.test.lucene.playground.helpers;

import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackTestLog;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackHighlighterTest {

    private static List<Track> corpus;
    private static PlaygroundLuceneHelper index;

    @BeforeAll
    static void rebuild() throws Exception {
        corpus = TrackDatasetLoader.load();
        index = new PlaygroundLuceneHelper();
        index.rebuild(corpus);
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void bob_wrapsMatchingTitle() throws Exception {
        List<Map<String, String>> highlights = highlight("Bob");
        assertThat(highlights)
                .anySatisfy(fields -> assertThat(fields.get(TrackDocumentMapper.TITLE))
                        .contains("Free (<b>Bob</b> Sinclar Remix)"));
    }

    @Test
    void nate_wrapsFoldedArtistAccent() throws Exception {
        List<Map<String, String>> highlights = highlight("nate");
        assertThat(highlights)
                .anySatisfy(fields -> assertThat(fields.get(TrackDocumentMapper.ARTIST))
                        .contains("<b>Naté</b>"));
    }

    @Test
    void lastTokenPrefix_wrapsFullTerm() throws Exception {
        List<Map<String, String>> highlights = highlight("sincla");
        assertThat(highlights)
                .anySatisfy(fields -> assertThat(fields.get(TrackDocumentMapper.TITLE))
                        .contains("<b>Sinclar</b>"));
    }

    @Test
    void matchAll_leavesStoredTextUnmarked() throws Exception {
        List<Map<String, String>> highlights = highlight("");
        assertThat(highlights).isNotEmpty();
        assertThat(highlights.getFirst().get(TrackDocumentMapper.TITLE)).doesNotContain("<b>");
        assertThat(highlights.getFirst().get(TrackDocumentMapper.ARTIST)).isNotBlank();
    }

    private static List<Map<String, String>> highlight(String q) throws Exception {
        Query lucene = TrackLuceneQueryBuilder.buildStructured(q, Map.of(), Map.of());
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            TopDocs top = searcher.search(lucene, 25);
            List<Map<String, String>> highlights = TrackHighlighter.highlight(searcher, lucene, top);
            TrackTestLog.highlight(q, highlights);
            return highlights;
        }
    }
}
