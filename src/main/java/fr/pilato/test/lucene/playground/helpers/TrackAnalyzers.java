package fr.pilato.test.lucene.playground.helpers;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

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
        return collect(text, searchAnalyzer()).stream().map(AnalyzedToken::term).toList();
    }

    /**
     * Same chain as {@link #searchAnalyzer()}, collected after each component
     * so a demo can show tokenizer → lowercase → ASCII folding.
     */
    public static List<Stage> pipeline(String text) {
        return List.of(
                new Stage("StandardTokenizer", collect(text, standardTokenizer())),
                new Stage("LowerCaseFilter", collect(text, tokenizerAndLowerCase())),
                new Stage("ASCIIFoldingFilter", collect(text, searchAnalyzer())));
    }

    private static Analyzer standardTokenizer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                return new TokenStreamComponents(new StandardTokenizer());
            }
        };
    }

    private static Analyzer tokenizerAndLowerCase() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                return new TokenStreamComponents(source, new LowerCaseFilter(source));
            }
        };
    }

    private static List<AnalyzedToken> collect(String text, Analyzer analyzer) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<AnalyzedToken> tokens = new ArrayList<>();
        try (analyzer;
             TokenStream stream = analyzer.tokenStream(TrackDocumentMapper.TITLE, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(new AnalyzedToken(term.toString(), offset.startOffset(), offset.endOffset()));
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to analyze text", e);
        }
        return List.copyOf(tokens);
    }

    public record AnalyzedToken(String term, int start, int end) {}

    public record Stage(String component, List<AnalyzedToken> tokens) {}
}
