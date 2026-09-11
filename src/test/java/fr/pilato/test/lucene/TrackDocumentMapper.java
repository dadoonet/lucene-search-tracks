package fr.pilato.test.lucene;

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

    private TrackDocumentMapper() {}

    public static Document toDocument(Track t) {
        Document doc = new Document();
        doc.add(new StringField(TrackIndexFields.ID, t.id(), Field.Store.YES));
        addText(doc, TrackIndexFields.TITLE, t.title());
        addText(doc, TrackIndexFields.ARTIST, t.artist());
        addText(doc, TrackIndexFields.GENRE, t.genre());
        addText(doc, TrackIndexFields.ALBUM, t.album());
        addText(doc, TrackIndexFields.LABEL, t.label());
        addText(doc, TrackIndexFields.COMMENT, t.comment());
        addKeyword(doc, TrackIndexFields.TITLE_RAW, TrackIndexFields.TITLE_RAW_NORMALIZED, t.title());
        addKeyword(doc, TrackIndexFields.ARTIST_RAW, TrackIndexFields.ARTIST_RAW_NORMALIZED, t.artist());
        addKeyword(doc, TrackIndexFields.GENRE_RAW, TrackIndexFields.GENRE_RAW_NORMALIZED, t.genre());
        doc.add(new StringField(TrackIndexFields.KEY_CODE, normalize(t.key()), Field.Store.YES));
        doc.add(new DoubleField(TrackIndexFields.BPM, t.bpm(), Field.Store.YES));
        doc.add(new IntField(TrackIndexFields.RATING, t.rating(), Field.Store.YES));
        doc.add(new IntField(TrackIndexFields.YEAR, t.year(), Field.Store.YES));
        String genre = nfc(t.genre());
        if (!genre.isEmpty()) {
            doc.add(new SortedSetDocValuesFacetField("genre", genre));
        }
        return doc;
    }

    static String nfc(String s) {
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
