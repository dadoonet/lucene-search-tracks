package fr.pilato.test.lucene.playground;

import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackAnalyzers;
import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackDocumentMapper;
import fr.pilato.test.lucene.TrackFacets;
import fr.pilato.test.lucene.TrackLuceneQueryBuilder;
import fr.pilato.test.lucene.TrackSearchIndex;
import fr.pilato.test.lucene.TrackSuggestion;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredValue;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.LongValueFacetCounts;
import org.apache.lucene.facet.MultiFacets;
import org.apache.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.pilato.test.lucene.playground.PlaygroundModels.AnalyzeResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.AnalyzeStage;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetBucket;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetDim;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetRewrite;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetRewriteLine;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetsResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.IndexResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.MapResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.MappedField;
import static fr.pilato.test.lucene.playground.PlaygroundModels.MetaResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchHitView;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchRequest;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SuggestHitView;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SuggestResponse;
import static fr.pilato.test.lucene.playground.PlaygroundModels.TermPosting;
import static fr.pilato.test.lucene.playground.PlaygroundModels.TrackPick;

public final class PlaygroundService implements AutoCloseable {

    public static final String REFERENCE_ID = "255465792";
    public static final String AROUND_THE_WORLD_ID = "172523747";
    public static final String CETTE_ANNEE_LA_ID = "106352474";
    public static final int TOP_HITS = 25;
    public static final int POSTING_LIMIT = 8;
    public static final Set<String> INDEX_TEXT_FIELDS = Set.of(
            TrackDocumentMapper.TITLE,
            TrackDocumentMapper.ARTIST,
            TrackDocumentMapper.GENRE,
            TrackDocumentMapper.ALBUM,
            TrackDocumentMapper.LABEL,
            TrackDocumentMapper.COMMENT);

    private final List<Track> corpus;
    private final Map<String, Track> byId;
    private final TrackSearchIndex index;

    public PlaygroundService(List<Track> corpus, TrackSearchIndex index) {
        this.corpus = List.copyOf(corpus);
        this.byId = new LinkedHashMap<>();
        for (Track track : this.corpus) {
            this.byId.put(track.id(), track);
        }
        this.index = index;
    }

    public static PlaygroundService boot() throws IOException {
        List<Track> corpus = TrackDatasetLoader.load();
        TrackSearchIndex index = new TrackSearchIndex();
        index.rebuild(corpus);
        return new PlaygroundService(corpus, index);
    }

    public MetaResponse meta() {
        return new MetaResponse(corpus.size(), index.numDocs(), "ByteBuffersDirectory");
    }

    public AnalyzeResponse analyze(String text) {
        String value = text == null ? "" : text;
        List<TrackAnalyzers.Stage> pipeline = TrackAnalyzers.pipeline(value);
        List<AnalyzeStage> stages = pipeline.stream()
                .map(stage -> new AnalyzeStage(
                        stage.component(),
                        stage.tokens().stream().map(TrackAnalyzers.AnalyzedToken::term).toList()))
                .toList();
        List<String> tokens = stages.isEmpty()
                ? List.of()
                : stages.getLast().tokens();
        List<PlaygroundModels.TokenSpan> spans = pipeline.isEmpty()
                ? List.of()
                : pipeline.getFirst().tokens().stream()
                        .map(token -> new PlaygroundModels.TokenSpan(
                                token.term(), token.start(), token.end()))
                        .toList();
        return new AnalyzeResponse(
                value, stages, tokens, PlaygroundAnalyze.rows(value, pipeline), spans);
    }

    public MapResponse map(String trackId) {
        Track track = trackId == null || trackId.isBlank()
                ? byId.get(REFERENCE_ID)
                : byId.get(trackId);
        if (track == null) {
            track = byId.get(REFERENCE_ID);
        }
        Document doc = TrackDocumentMapper.toDocument(track);
        List<MappedField> fields = new ArrayList<>();
        for (IndexableField field : doc.getFields()) {
            String type = field.getClass().getSimpleName();
            String name = field.name();
            String value = stored(field);
            if (field instanceof SortedSetDocValuesFacetField facet) {
                name = facet.dim;
                value = String.join("/", facet.path);
            }
            boolean tokenized = field.fieldType().tokenized();
            List<String> tokens = tokenized
                    ? TrackAnalyzers.tokenize(value).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()
                    : List.of();
            String role = role(name, type);
            String ieee = ieeeNote(field);
            if (!ieee.isEmpty()) {
                role = role + " · " + ieee;
            }
            fields.add(new MappedField(name, type, tokenized, value, tokens, role));
        }
        return new MapResponse(
                track.id(),
                track.title(),
                track.artist(),
                track.genre(),
                track.key(),
                track.bpm(),
                track.rating(),
                track.year(),
                track.album(),
                track.label(),
                track.comment(),
                List.copyOf(fields),
                mapPicks());
    }

    private List<TrackPick> mapPicks() {
        return List.of(
                pick(AROUND_THE_WORLD_ID, "Around The World"),
                pick(REFERENCE_ID, "Ultra Naté"),
                pick(CETTE_ANNEE_LA_ID, "Cette année-là"));
    }

    private TrackPick pick(String id, String label) {
        Track track = byId.get(id);
        if (track == null) {
            return new TrackPick(id, "", label, label);
        }
        return new TrackPick(track.id(), track.artist(), track.title(), label);
    }

    public IndexResponse invertedIndex(String term) throws IOException {
        return invertedIndex(term, TrackDocumentMapper.TITLE);
    }

    public IndexResponse invertedIndex(String term, String fieldName) throws IOException {
        String needle = (term == null || term.isBlank()) ? "bob" : TrackAnalyzers.tokenize(term)
                .stream()
                .findFirst()
                .orElse(term.toLowerCase());
        String field = INDEX_TEXT_FIELDS.contains(fieldName)
                ? fieldName
                : TrackDocumentMapper.TITLE;
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            Terms terms = MultiTerms.getTerms(reader, field);
            long docFreq = 0;
            List<TermPosting> postings = new ArrayList<>();
            if (terms != null) {
                TermsEnum iterator = terms.iterator();
                if (iterator.seekExact(new BytesRef(needle))) {
                    docFreq = iterator.docFreq();
                    PostingsEnum docs = iterator.postings(null, PostingsEnum.FREQS);
                    int shown = 0;
                    int docId;
                    while ((docId = docs.nextDoc()) != PostingsEnum.NO_MORE_DOCS && shown < POSTING_LIMIT) {
                        Document stored = reader.storedFields().document(docId);
                        postings.add(new TermPosting(
                                stored.get(TrackDocumentMapper.TITLE),
                                stored.get(TrackDocumentMapper.ARTIST),
                                docs.freq()));
                        shown++;
                    }
                }
            }
            return new IndexResponse(
                    corpus.size(),
                    index.numDocs(),
                    "ByteBuffersDirectory",
                    needle,
                    field,
                    docFreq,
                    List.copyOf(postings));
        }
    }

    public SearchResponse search(SearchRequest request) throws IOException {
        String q = request == null || request.q() == null ? "" : request.q();
        Map<String, List<String>> filters = request == null || request.filters() == null
                ? Map.of() : request.filters();
        Map<String, List<String>> mustNots = request == null || request.mustNots() == null
                ? Map.of() : request.mustNots();
        Query lucene = TrackLuceneQueryBuilder.buildStructured(q, filters, mustNots);
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            TopDocs top = searcher.search(lucene, TOP_HITS);
            int total = Math.toIntExact(top.totalHits.value());
            Integer explainDoc = request == null ? null : request.explainDoc();
            List<SearchHitView> hits = new ArrayList<>();
            for (ScoreDoc hit : top.scoreDocs) {
                var idField = searcher.storedFields()
                        .document(hit.doc)
                        .getField(TrackDocumentMapper.ID);
                if (idField == null) {
                    continue;
                }
                Track track = byId.get(idField.stringValue());
                if (track == null) {
                    continue;
                }
                String explain = null;
                if (explainDoc != null && explainDoc == hit.doc) {
                    explain = searcher.explain(lucene, hit.doc).toString();
                }
                hits.add(new SearchHitView(
                        hit.doc,
                        track.id(),
                        track.title(),
                        track.artist(),
                        track.genre(),
                        track.key(),
                        track.bpm(),
                        track.rating(),
                        track.year(),
                        hit.score,
                        explain));
            }
            if (explainDoc == null && !hits.isEmpty()) {
                SearchHitView first = hits.getFirst();
                String explain = searcher.explain(lucene, first.luceneDoc()).toString();
                hits.set(0, new SearchHitView(
                        first.luceneDoc(),
                        first.id(),
                        first.title(),
                        first.artist(),
                        first.genre(),
                        first.key(),
                        first.bpm(),
                        first.rating(),
                        first.year(),
                        first.score(),
                        explain));
            }
            return new SearchResponse(
                    q,
                    TrackAnalyzers.tokenize(q),
                    lucene.toString(),
                    total,
                    List.copyOf(hits));
        }
    }

    public SuggestResponse suggest(String prefix) throws IOException {
        String value = prefix == null ? "" : prefix;
        List<SuggestHitView> hits = new ArrayList<>();
        for (TrackSuggestion suggestion : index.suggest(value)) {
            hits.add(new SuggestHitView(suggestion.text(), suggestion.field(), suggestion.highlight()));
        }
        return new SuggestResponse(value, List.copyOf(hits));
    }

    public FacetsResponse facets(String q, String drillGenre) throws IOException {
        String queryText = q == null ? "" : q;
        String drill = drillGenre == null || drillGenre.isBlank() ? "" : drillGenre;
        Query base = TrackLuceneQueryBuilder.buildStructured(queryText, Map.of(), Map.of());
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, TrackFacets.config());
            Facets facets;
            boolean sideways = !drill.isEmpty();
            if (sideways) {
                DrillDownQuery drillDown = new DrillDownQuery(TrackFacets.config(), base);
                drillDown.add(TrackFacets.GENRE, new TermQuery(new Term(TrackDocumentMapper.GENRE_RAW, drill)));
                facets = new PlaygroundDrillSideways(searcher, state).search(drillDown, 1).facets;
            } else {
                FacetsCollector collector = FacetsCollectorManager.search(
                                searcher, base, 1, new FacetsCollectorManager())
                        .facetsCollector();
                facets = mix(state, collector);
            }
            return new FacetsResponse(
                    queryText,
                    base.toString(),
                    sideways,
                    drill,
                    List.of(
                            dim("genre", "🏷️", children(facets.getAllChildren(TrackFacets.GENRE), 12)),
                            dim("bpm", "⏱", children(facets.getAllChildren(TrackDocumentMapper.BPM), 16)),
                            dim("rating", "⭐", children(facets.getAllChildren(TrackDocumentMapper.RATING), 6)),
                            dim("year", "📅", decades(facets.getAllChildren(TrackDocumentMapper.YEAR)))),
                    facetRewrite());
        }
    }

    @Override
    public void close() throws IOException {
        index.close();
    }

    private static Facets mix(DefaultSortedSetDocValuesReaderState state, FacetsCollector hits)
            throws IOException {
        FacetsCollector collector = hits != null ? hits : new FacetsCollector();
        Map<String, Facets> byDim = new LinkedHashMap<>();
        Facets ssdv = new SortedSetDocValuesFacetCounts(state, collector);
        byDim.put(TrackFacets.GENRE, ssdv);
        byDim.put(TrackDocumentMapper.BPM, new DoubleRangeFacetCounts(
                TrackDocumentMapper.BPM, collector, TrackFacets.bpmRanges()));
        byDim.put(TrackDocumentMapper.RATING, new LongValueFacetCounts(
                TrackDocumentMapper.RATING, collector));
        byDim.put(TrackDocumentMapper.YEAR, new LongValueFacetCounts(
                TrackDocumentMapper.YEAR, collector));
        return new MultiFacets(byDim);
    }

    private FacetRewrite facetRewrite() throws IOException {
        Track track = byId.get(REFERENCE_ID);
        if (track == null) {
            return new FacetRewrite(List.of(), List.of());
        }
        Document before = TrackDocumentMapper.toDocument(track);
        Document after = TrackFacets.config().build(before);
        return new FacetRewrite(facetRewriteLines(before), facetRewriteLines(after));
    }

    private static List<FacetRewriteLine> facetRewriteLines(Document doc) {
        List<FacetRewriteLine> lines = new ArrayList<>();
        for (IndexableField field : doc.getFields()) {
            if (field instanceof SortedSetDocValuesFacetField facet) {
                lines.add(new FacetRewriteLine(
                        "SortedSetDocValuesFacetField",
                        facet.dim,
                        String.join("/", facet.path),
                        "page 2"));
                continue;
            }
            if (!FacetsConfig.DEFAULT_INDEX_FIELD_NAME.equals(field.name())) {
                continue;
            }
            String type = field.getClass().getSimpleName();
            String role = "SortedSetDocValuesField".equals(type) ? "counts" : "drill-down";
            lines.add(new FacetRewriteLine(type, field.name(), facetFieldValue(field), role));
        }
        return List.copyOf(lines);
    }

    private static String facetFieldValue(IndexableField field) {
        String text = field.stringValue();
        if (text != null) {
            return visibleDelim(text);
        }
        BytesRef binary = field.binaryValue();
        return binary == null ? "" : visibleDelim(binary.utf8ToString());
    }

    private static String visibleDelim(String value) {
        return value.replace(String.valueOf(FacetsConfig.DELIM_CHAR), "\\u001F");
    }

    private static FacetDim dim(String name, String emoji, List<FacetBucket> buckets) {
        return new FacetDim(name, emoji, buckets);
    }

    private static List<FacetBucket> children(FacetResult result, int limit) {
        if (result == null || result.labelValues == null) {
            return List.of();
        }
        List<FacetBucket> buckets = new ArrayList<>();
        for (LabelAndValue value : result.labelValues) {
            if (value.value.longValue() <= 0) {
                continue;
            }
            buckets.add(new FacetBucket(value.label, value.value.longValue()));
        }
        buckets.sort((a, b) -> Long.compare(b.count(), a.count()));
        if (buckets.size() > limit) {
            return List.copyOf(buckets.subList(0, limit));
        }
        return List.copyOf(buckets);
    }

    private static List<FacetBucket> decades(FacetResult result) {
        if (result == null || result.labelValues == null) {
            return List.of();
        }
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (LabelAndValue value : result.labelValues) {
            int year;
            try {
                year = Integer.parseInt(value.label);
            } catch (NumberFormatException e) {
                continue;
            }
            if (year <= 0) {
                continue;
            }
            int decade = (year / 10) * 10;
            String label = decade + "–" + (decade + 9);
            grouped.merge(label, value.value.longValue(), Long::sum);
        }
        return grouped.entrySet().stream()
                .map(entry -> new FacetBucket(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String stored(IndexableField field) {
        StoredValue stored = field.storedValue();
        if (stored != null) {
            return switch (stored.getType()) {
                case DOUBLE -> Double.toString(stored.getDoubleValue());
                case FLOAT -> Float.toString(stored.getFloatValue());
                case INTEGER -> Integer.toString(stored.getIntValue());
                case LONG -> Long.toString(stored.getLongValue());
                case STRING -> stored.getStringValue();
                case BINARY, DATA_INPUT -> {
                    String text = field.stringValue();
                    yield text != null ? text : "";
                }
            };
        }
        String text = field.stringValue();
        if (text != null) {
            return text;
        }
        Number number = field.numericValue();
        return number == null ? "" : number.toString();
    }

    private static String ieeeNote(IndexableField field) {
        StoredValue stored = field.storedValue();
        if (stored == null || stored.getType() != StoredValue.Type.DOUBLE) {
            return "";
        }
        Number bits = field.numericValue();
        if (bits == null) {
            return "packed as IEEE 754 bits — read storedValue().getDoubleValue()";
        }
        return "packed as IEEE 754 bits (numericValue() = 0x"
                + Long.toHexString(bits.longValue()).toUpperCase() + ")";
    }

    private static String role(String name, String luceneType) {
        if (luceneType.contains("Facet")) {
            return "facet dimension";
        }
        return switch (name) {
            case TrackDocumentMapper.ID -> "stored join key";
            case TrackDocumentMapper.TITLE,
                 TrackDocumentMapper.ARTIST,
                 TrackDocumentMapper.GENRE,
                 TrackDocumentMapper.ALBUM,
                 TrackDocumentMapper.LABEL,
                 TrackDocumentMapper.COMMENT -> "analyzed free text";
            case TrackDocumentMapper.TITLE_RAW,
                 TrackDocumentMapper.ARTIST_RAW,
                 TrackDocumentMapper.GENRE_RAW -> "keyword display";
            case TrackDocumentMapper.TITLE_RAW_NORMALIZED,
                 TrackDocumentMapper.ARTIST_RAW_NORMALIZED,
                 TrackDocumentMapper.GENRE_RAW_NORMALIZED -> "exact FILTER";
            case TrackDocumentMapper.KEY_CODE -> "exact FILTER / MUST_NOT";
            case TrackDocumentMapper.BPM -> "numeric range + facets";
            case TrackDocumentMapper.RATING, TrackDocumentMapper.YEAR -> "numeric facets";
            default -> "stored field";
        };
    }

    private static final class PlaygroundDrillSideways extends DrillSideways {
        private final DefaultSortedSetDocValuesReaderState state;

        private PlaygroundDrillSideways(IndexSearcher searcher, DefaultSortedSetDocValuesReaderState state) {
            super(searcher, TrackFacets.config(), state);
            this.state = state;
        }

        @Override
        protected Facets buildFacetsResult(
                FacetsCollector drillDowns,
                FacetsCollector[] drillSideways,
                String[] drillSidewaysDims) throws IOException {
            Facets drillDownFacets = mix(state, drillDowns);
            if (drillSideways == null || drillSideways.length == 0) {
                return drillDownFacets;
            }
            Map<String, Facets> sideways = new LinkedHashMap<>();
            for (int i = 0; i < drillSideways.length; i++) {
                sideways.put(drillSidewaysDims[i], mix(state, drillSideways[i]));
            }
            return new MultiFacets(sideways, drillDownFacets);
        }
    }
}
