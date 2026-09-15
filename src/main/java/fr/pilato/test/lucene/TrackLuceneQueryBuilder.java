package fr.pilato.test.lucene;

import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.IntField;
import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TrackLuceneQueryBuilder {

    private static final float TITLE_BOOST = 4.0f;
    private static final float ARTIST_BOOST = 3.0f;
    private static final float GENRE_BOOST = 2.0f;
    private static final float ALBUM_BOOST = 1.5f;
    private static final float LABEL_BOOST = 1.0f;
    private static final float COMMENT_BOOST = 0.5f;
    private static final float PREFIX_BOOST = 0.25f;

    private TrackLuceneQueryBuilder() {}

    public static Query buildStructured(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots) {
        Query text = analyzedFreeText(q);
        boolean hasText = !(text instanceof MatchAllDocsQuery);

        BooleanQuery.Builder result = new BooleanQuery.Builder();
        if (hasText) {
            result.add(text, BooleanClause.Occur.MUST);
        }
        int filterCount = addClauses(result, filters, BooleanClause.Occur.FILTER);
        int notCount = addClauses(result, mustNots, BooleanClause.Occur.MUST_NOT);
        if (!hasText && filterCount == 0 && notCount == 0) {
            return new MatchAllDocsQuery();
        }
        if (!hasText && filterCount == 0 && notCount > 0) {
            result.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        }
        if (hasText && filterCount == 0 && notCount == 0) {
            return text;
        }
        return result.build();
    }

    public static List<Track> search(IndexSearcher searcher, Query query, List<Track> corpus)
            throws IOException {
        return searchHits(searcher, query, corpus).stream().map(TrackHit::track).toList();
    }

    public static List<TrackHit> searchHits(IndexSearcher searcher, Query query, List<Track> corpus)
            throws IOException {
        Map<String, Track> byId = new HashMap<>();
        for (Track track : corpus) {
            byId.put(track.id(), track);
        }
        int limit = Math.max(1, searcher.getIndexReader().numDocs());
        TopDocs hits = searcher.search(query, limit);
        List<TrackHit> ordered = new ArrayList<>();
        for (ScoreDoc hit : hits.scoreDocs) {
            IndexableField idField = searcher.storedFields()
                    .document(hit.doc)
                    .getField(TrackDocumentMapper.ID);
            if (idField == null) {
                continue;
            }
            Track track = byId.get(idField.stringValue());
            if (track != null) {
                ordered.add(new TrackHit(track, hit.score));
            }
        }
        return List.copyOf(ordered);
    }

    private static int addClauses(
            BooleanQuery.Builder result,
            Map<String, List<String>> clauses,
            BooleanClause.Occur occur) {
        if (clauses == null || clauses.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, List<String>> entry : clauses.entrySet()) {
            Query clause = fieldFilter(entry.getKey(), entry.getValue());
            if (clause == null) {
                continue;
            }
            result.add(clause, occur);
            count++;
        }
        return count;
    }

    private static Query analyzedFreeText(String text) {
        if (text == null || text.isBlank()) {
            return new MatchAllDocsQuery();
        }
        List<String> tokens = TrackAnalyzers.tokenize(text);
        if (tokens.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        return freeTextFromTokens(tokens);
    }

    private static Query freeTextFromTokens(List<String> tokens) {
        List<Query> tokenQueries = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            boolean prefix = i == tokens.size() - 1;
            tokenQueries.add(freeTextQuery(tokens.get(i), prefix));
        }
        if (tokenQueries.size() == 1) {
            return tokenQueries.getFirst();
        }
        BooleanQuery.Builder and = new BooleanQuery.Builder();
        for (Query tokenQuery : tokenQueries) {
            and.add(tokenQuery, BooleanClause.Occur.MUST);
        }
        return and.build();
    }

    private static Query freeTextQuery(String token, boolean prefix) {
        BooleanQuery.Builder fields = new BooleanQuery.Builder();
        addFreeTextField(fields, TrackDocumentMapper.TITLE, TITLE_BOOST, token, prefix);
        addFreeTextField(fields, TrackDocumentMapper.ARTIST, ARTIST_BOOST, token, prefix);
        addFreeTextField(fields, TrackDocumentMapper.GENRE, GENRE_BOOST, token, prefix);
        addFreeTextField(fields, TrackDocumentMapper.ALBUM, ALBUM_BOOST, token, prefix);
        addFreeTextField(fields, TrackDocumentMapper.LABEL, LABEL_BOOST, token, prefix);
        addFreeTextField(fields, TrackDocumentMapper.COMMENT, COMMENT_BOOST, token, prefix);
        fields.setMinimumNumberShouldMatch(1);
        return fields.build();
    }

    private static void addFreeTextField(
            BooleanQuery.Builder fields, String field, float boost, String token, boolean prefix) {
        fields.add(
                new BoostQuery(new TermQuery(new Term(field, token)), boost),
                BooleanClause.Occur.SHOULD);
        if (prefix && token.length() >= TrackAnalyzers.PREFIX_MIN) {
            fields.add(
                    new BoostQuery(new PrefixQuery(new Term(field, token)), boost * PREFIX_BOOST),
                    BooleanClause.Occur.SHOULD);
        }
    }

    private static Query fieldFilter(String field, List<String> values) {
        if (field == null || field.isBlank() || values == null || values.isEmpty()) {
            return null;
        }
        List<Query> parts = new ArrayList<>();
        for (String value : values) {
            Query leaf = fieldQuery(field, value == null ? "" : value);
            if (leaf != null) {
                parts.add(leaf);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        BooleanQuery.Builder sameField = new BooleanQuery.Builder();
        for (Query queryPart : parts) {
            sameField.add(queryPart, BooleanClause.Occur.SHOULD);
        }
        sameField.setMinimumNumberShouldMatch(1);
        return sameField.build();
    }

    private static Query fieldQuery(String field, String value) {
        return switch (field) {
            case "title" -> keyword(TrackDocumentMapper.TITLE_RAW_NORMALIZED, value);
            case "artist" -> keyword(TrackDocumentMapper.ARTIST_RAW_NORMALIZED, value);
            case "genre" -> keyword(TrackDocumentMapper.GENRE_RAW_NORMALIZED, value);
            case "key" -> keyword(TrackDocumentMapper.KEY_CODE, value);
            case "bpm" -> bpmRange(value);
            case "rating" -> ratingExact(value);
            case "year" -> yearDecade(value);
            default -> null;
        };
    }

    private static Query keyword(String luceneField, String value) {
        return new TermQuery(new Term(luceneField, TrackDocumentMapper.normalize(value)));
    }

    /** Facet buckets are half-open: {@code 120 – 130} is {@code [120, 130)}. */
    private static Query bpmRange(String label) {
        for (DoubleRange range : TrackFacets.bpmRanges()) {
            if (range.label.equals(label)) {
                double max = Double.isInfinite(range.max)
                        ? Double.POSITIVE_INFINITY
                        : Math.nextDown(range.max);
                return DoubleField.newRangeQuery(TrackDocumentMapper.BPM, range.min, max);
            }
        }
        return null;
    }

    private static Query ratingExact(String label) {
        try {
            return IntField.newExactQuery(TrackDocumentMapper.RATING, Integer.parseInt(label.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Playground decade labels look like {@code 2020–2029} (en dash). */
    private static Query yearDecade(String label) {
        String[] parts = label.split("–", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            int from = Integer.parseInt(parts[0].trim());
            int to = Integer.parseInt(parts[1].trim());
            return IntField.newRangeQuery(TrackDocumentMapper.YEAR, from, to);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
