package fr.pilato.test.lucene;

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
        String luceneField = switch (field) {
            case "title" -> TrackDocumentMapper.TITLE_RAW_NORMALIZED;
            case "artist" -> TrackDocumentMapper.ARTIST_RAW_NORMALIZED;
            case "genre" -> TrackDocumentMapper.GENRE_RAW_NORMALIZED;
            case "key" -> TrackDocumentMapper.KEY_CODE;
            default -> null;
        };
        if (luceneField == null) {
            return null;
        }
        return new TermQuery(new Term(luceneField, TrackDocumentMapper.normalize(value)));
    }
}
