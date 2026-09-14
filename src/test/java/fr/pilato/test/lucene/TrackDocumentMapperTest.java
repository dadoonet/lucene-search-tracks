package fr.pilato.test.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackDocumentMapperTest {

    @Test
    void toDocument_indexesIdentityTextKeywordsAndNumerics() throws Exception {
        Track track = new Track(
                "255465792",
                "Free (Bob Sinclar Remix)",
                "Ultra Naté",
                "Club",
                "4B",
                128.0,
                3,
                1997,
                "Album",
                "Label",
                "comment");

        Document doc = TrackDocumentMapper.toDocument(track);
        TrackTestLog.mapping(
                track,
                tokens(TrackDocumentMapper.TITLE, doc.get(TrackDocumentMapper.TITLE)),
                tokens(TrackDocumentMapper.ARTIST, "Ultra Naté"));

        assertThat(doc.get(TrackDocumentMapper.ID)).isEqualTo("255465792");
        assertThat(tokens(TrackDocumentMapper.TITLE, doc.get(TrackDocumentMapper.TITLE)))
                .contains("free", "bob", "sinclar", "remix");
        assertThat(tokens(TrackDocumentMapper.ARTIST, "Ultra Naté")).contains("ultra", "nate");
        assertThat(doc.get(TrackDocumentMapper.GENRE_RAW)).isEqualTo("Club");
        assertThat(doc.get(TrackDocumentMapper.GENRE_RAW_NORMALIZED)).isEqualTo("club");
        assertThat(doc.get(TrackDocumentMapper.KEY_CODE)).isEqualTo("4b");
        assertThat(doc.getField(TrackDocumentMapper.BPM).storedValue().getDoubleValue()).isEqualTo(128.0);
        assertThat(doc.getField(TrackDocumentMapper.RATING).storedValue().getIntValue()).isEqualTo(3);
        assertThat(names(doc, TrackDocumentMapper.TITLE_RAW)).isNotEmpty();
        assertThat(names(doc, TrackDocumentMapper.ARTIST_RAW)).isNotEmpty();
    }

    private static List<String> names(Document doc, String field) {
        List<String> names = new ArrayList<>();
        for (IndexableField f : doc.getFields()) {
            if (field.equals(f.name())) {
                names.add(f.stringValue());
            }
        }
        return names;
    }

    private static List<String> tokens(String field, String text) throws Exception {
        List<String> tokens = new ArrayList<>();
        try (Analyzer analyzer = TrackAnalyzers.searchAnalyzer();
             TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(term.toString());
            }
            stream.end();
        }
        return tokens;
    }
}
