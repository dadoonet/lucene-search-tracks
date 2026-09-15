package fr.pilato.test.lucene.playground.helpers;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.WholeBreakIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrackHighlighter {

    public static final String[] FIELDS = {
            TrackDocumentMapper.TITLE,
            TrackDocumentMapper.ARTIST,
            TrackDocumentMapper.GENRE,
            TrackDocumentMapper.ALBUM,
            TrackDocumentMapper.LABEL,
            TrackDocumentMapper.COMMENT
    };

    private TrackHighlighter() {}

    public static List<Map<String, String>> highlight(
            IndexSearcher searcher, Query query, TopDocs topDocs) throws IOException {
        if (topDocs == null || topDocs.scoreDocs.length == 0) {
            return List.of();
        }
        try (Analyzer analyzer = TrackAnalyzers.searchAnalyzer()) {
            UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                    .withMaxLength(10_000)
                    .withBreakIterator(WholeBreakIterator::new)
                    .build();
            Map<String, String[]> byField = highlighter.highlightFields(FIELDS, query, topDocs);
            List<Map<String, String>> hits = new ArrayList<>(topDocs.scoreDocs.length);
            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String field : FIELDS) {
                    String[] snippets = byField.get(field);
                    String snippet = snippets == null ? null : snippets[i];
                    if (snippet != null && !snippet.isBlank()) {
                        fields.put(field, snippet);
                    }
                }
                hits.add(Map.copyOf(fields));
            }
            return List.copyOf(hits);
        }
    }
}
