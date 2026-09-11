package fr.pilato.test.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public final class TrackAnalyzers {

    /** Minimum length of a last-token prefix ({@code PrefixQuery} / in-memory startsWith). */
    public static final int PREFIX_MIN = 1;

    private TrackAnalyzers() {}

    /**
     * Search and index analyzer: standard tokenization + lowercase + ASCII folding.
     *
     * <p>No stop-word filter — titles like {@code Around The World} must stay searchable.
     * Accent folding lets {@code nate} match {@code Naté}. Stored field values remain NFC
     * as written by {@link TrackDocumentMapper}. Prefix matching is a query-time
     * {@code PrefixQuery} on the last token, not extra indexed n-grams.
     */
    public static Analyzer searchAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                TokenStream filter = new LowerCaseFilter(source);
                filter = new ASCIIFoldingFilter(filter);
                return new TokenStreamComponents(source, filter);
            }
        };
    }

    /** Tokenizes {@code text} with {@link #searchAnalyzer()}. */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        try (Analyzer analyzer = searchAnalyzer();
             TokenStream stream = analyzer.tokenStream(TrackIndexFields.TITLE, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to analyze text", e);
        }
        return List.copyOf(tokens);
    }
}
