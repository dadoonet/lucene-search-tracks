package fr.pilato.test.lucene.playground;

import java.util.List;
import java.util.Map;

public final class PlaygroundModels {

    private PlaygroundModels() {}

    public record TokenSpan(String term, int start, int end) {}

    public record AnalyzeCell(String text, String state) {}

    public record AnalyzeRow(List<AnalyzeCell> cells) {}

    public record AnalyzeStage(String component, List<String> tokens) {}

    public record AnalyzeResponse(
            String text,
            List<AnalyzeStage> stages,
            List<String> tokens,
            List<AnalyzeRow> rows,
            List<TokenSpan> spans) {}

    public record TrackPick(String id, String artist, String title, String label) {}

    public record MappedField(
            String name,
            String luceneType,
            boolean tokenized,
            String value,
            List<String> tokens,
            String role) {}

    public record MapResponse(
            String id,
            String title,
            String artist,
            String genre,
            String key,
            double bpm,
            int rating,
            int year,
            String album,
            String label,
            String comment,
            List<MappedField> fields,
            List<TrackPick> picks) {}

    public record TermPosting(String title, String artist, int freq) {}

    public record IndexResponse(
            int corpusSize,
            int numDocs,
            String directory,
            String term,
            String field,
            long docFreq,
            List<TermPosting> postings) {}

    public record ExplainNode(
            double value,
            String description,
            List<String> keys,
            List<ExplainNode> details) {}

    public record SearchHitView(
            int luceneDoc,
            String id,
            String title,
            String artist,
            String genre,
            String key,
            double bpm,
            int rating,
            int year,
            float score,
            String explain,
            ExplainNode explainTree,
            Map<String, String> highlights) {}

    public record SearchResponse(
            String q,
            List<String> tokens,
            String query,
            int total,
            List<SearchHitView> hits) {}

    public record SuggestHitView(String text, String field, String highlight) {}

    public record SuggestResponse(String prefix, List<SuggestHitView> hits) {}

    public record FacetBucket(String label, long count) {}

    public record FacetDim(String name, String emoji, List<FacetBucket> buckets) {}

    public record FacetRewriteLine(String luceneType, String name, String value, String role) {}

    public record FacetRewrite(List<FacetRewriteLine> before, List<FacetRewriteLine> after) {}

    public record FacetsResponse(
            String q,
            String query,
            boolean drillSideways,
            String drillGenre,
            List<FacetDim> dims,
            FacetRewrite rewrite) {}

    public record SearchRequest(
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots,
            Integer explainDoc) {}

    public record AnalyzeRequest(String text) {}

    public record MapRequest(String id) {}

    public record SuggestRequest(String prefix) {}

    public record FacetsRequest(
            String q,
            String drillGenre,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots) {}

    public record MetaResponse(int numDocs, String directory, String heapSize, long builtInMs) {}
}
