package fr.pilato.test.lucene.playground.helpers;

import fr.pilato.test.lucene.Track;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;

import java.text.Normalizer;
import java.util.Locale;

public final class TrackDocumentMapper {

    public static final String ID = "id";
    public static final String TITLE = "title";
    public static final String ARTIST = "artist";
    public static final String GENRE = "genre";
    public static final String ALBUM = "album";
    public static final String LABEL = "label";
    public static final String COMMENT = "comment";
    public static final String TITLE_RAW = "title.raw";
    public static final String TITLE_RAW_NORMALIZED = "title.raw.normalized";
    public static final String ARTIST_RAW = "artist.raw";
    public static final String ARTIST_RAW_NORMALIZED = "artist.raw.normalized";
    public static final String GENRE_RAW = "genre.raw";
    public static final String GENRE_RAW_NORMALIZED = "genre.raw.normalized";
    public static final String KEY_CODE = "key.code";
    public static final String BPM = "bpm";
    public static final String RATING = "rating";
    public static final String YEAR = "year";

    private TrackDocumentMapper() {}

    public static Document toDocument(Track t) {
        Document doc = new Document();
        doc.add(new StringField(ID, t.id(), Field.Store.YES));
        addText(doc, TITLE, t.title());
        addKeyword(doc, TITLE_RAW, TITLE_RAW_NORMALIZED, t.title());
        addText(doc, ARTIST, t.artist());
        addKeyword(doc, ARTIST_RAW, ARTIST_RAW_NORMALIZED, t.artist());
        addText(doc, GENRE, t.genre());
        addKeyword(doc, GENRE_RAW, GENRE_RAW_NORMALIZED, t.genre());
        String genre = nfc(t.genre());
        if (!genre.isEmpty()) {
            doc.add(new SortedSetDocValuesFacetField(GENRE, genre));
        }
        addText(doc, ALBUM, t.album());
        addText(doc, LABEL, t.label());
        addText(doc, COMMENT, t.comment());
        doc.add(new StringField(KEY_CODE, normalize(t.key()), Field.Store.YES));
        doc.add(new DoubleField(BPM, t.bpm(), Field.Store.YES));
        doc.add(new IntField(RATING, t.rating(), Field.Store.YES));
        doc.add(new IntField(YEAR, t.year(), Field.Store.YES));
        return doc;
    }

    private static String nfc(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    static String normalize(String s) {
        return nfc(s).toLowerCase(Locale.ROOT);
    }

    private static void addText(Document doc, String field, String value) {
        doc.add(new TextField(field, nfc(value), Field.Store.YES));
    }

    private static void addKeyword(Document doc, String raw, String normalized, String value) {
        String nfc = nfc(value);
        doc.add(new StringField(raw, nfc, Field.Store.YES));
        doc.add(new StringField(normalized, normalize(nfc), Field.Store.YES));
    }
}
