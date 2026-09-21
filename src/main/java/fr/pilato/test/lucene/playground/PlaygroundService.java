package fr.pilato.test.lucene.playground;

import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackFacetsResult;
import fr.pilato.test.lucene.TrackHit;
import fr.pilato.test.lucene.TrackSearch;
import fr.pilato.test.lucene.TrackSearchLuceneImpl;
import fr.pilato.test.lucene.TrackSuggestion;
import fr.pilato.test.lucene.playground.helpers.PlaygroundLuceneHelper;
import fr.pilato.test.lucene.playground.helpers.TrackAnalyzers;
import fr.pilato.test.lucene.playground.helpers.TrackDocumentMapper;
import fr.pilato.test.lucene.playground.helpers.TrackFacets;
import fr.pilato.test.lucene.playground.helpers.TrackLuceneQueryBuilder;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredValue;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private final TrackSearchLuceneImpl index;
    private final PlaygroundLuceneHelper luceneIndex;
    private final PlaygroundElasticsearch elasticsearch;
    private final String heapSize;
    private final long builtInMs;

    public PlaygroundService(
            List<Track> corpus,
            TrackSearchLuceneImpl index,
            PlaygroundLuceneHelper luceneIndex,
            String heapSize,
            long builtInMs) {
        this.corpus = List.copyOf(corpus);
        this.byId = new LinkedHashMap<>();
        for (Track track : this.corpus) {
            this.byId.put(track.id(), track);
        }
        this.index = index;
        this.luceneIndex = luceneIndex;
        this.elasticsearch = new PlaygroundElasticsearch(this.corpus, ElasticsearchSettings.fromEnv());
        this.heapSize = heapSize;
        this.builtInMs = builtInMs;
    }

    public static PlaygroundService boot() throws IOException {
        List<Track> corpus = TrackDatasetLoader.load();
        TrackSearchLuceneImpl index = new TrackSearchLuceneImpl();
        PlaygroundLuceneHelper luceneIndex = new PlaygroundLuceneHelper();
        long start = System.nanoTime();
        index.rebuild(corpus);
        luceneIndex.rebuild(corpus);
        long luceneMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println(IndexTiming.indexed("Lucene", corpus.size(), luceneMs));
        String heapSize = RamUsageEstimator.humanReadableUnits(luceneIndex.ramBytesUsed()).trim();
        PlaygroundService service = new PlaygroundService(corpus, index, luceneIndex, heapSize, luceneMs);
        var es = service.elasticsearch();
        if (ElasticsearchSettings.fromEnv().hasCredentials()) {
            var status = service.connectElasticsearch(es.url(), null);
            if (!status.ready()) {
                System.out.println("Elasticsearch skipped: " + status.error());
            }
        }
        return service;
    }

    public MetaResponse meta() {
        return new MetaResponse(luceneIndex.numDocs(), "ByteBuffersDirectory", heapSize, builtInMs);
    }

    public PlaygroundModels.ElasticsearchStatus elasticsearch() {
        return elasticsearch.status();
    }

    public PlaygroundModels.ElasticsearchStatus connectElasticsearch(String url, String apiKey) {
        return elasticsearch.connect(url, apiKey);
    }

    public PlaygroundModels.ElasticsearchStatus disconnectElasticsearch() {
        return elasticsearch.disconnect();
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
            // Search story (Map): facet-only fields appear later in Facets.
            if (field instanceof SortedSetDocValuesFacetField) {
                continue;
            }
            String type = field.getClass().getSimpleName();
            String name = field.name();
            String value = stored(field);
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
        IndexSearcher searcher = luceneIndex.searcher();
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
                    luceneIndex.numDocs(),
                    "ByteBuffersDirectory",
                    needle,
                    field,
                    docFreq,
                    List.copyOf(postings));
        }
    }

    public SearchResponse search(SearchRequest request, String backend) throws IOException {
        TrackSearch engine = engine(backend);
        String q = request == null || request.q() == null ? "" : request.q();
        Map<String, List<String>> filters = request == null || request.filters() == null
                ? Map.of() : request.filters();
        Map<String, List<String>> mustNots = request == null || request.mustNots() == null
                ? Map.of() : request.mustNots();
        long start = System.nanoTime();
        List<TrackHit> all;
        try {
            all = engine.search(q, filters, mustNots);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        int total = all.size();
        List<TrackHit> page = all.size() > TOP_HITS ? all.subList(0, TOP_HITS) : all;
        List<SearchHitView> hits = new ArrayList<>();
        for (TrackHit hit : page) {
            Track t = hit.track();
            hits.add(new SearchHitView(
                    0, t.id(), t.title(), t.artist(), t.genre(), t.key(), t.bpm(), t.rating(), t.year(),
                    hit.score(), null, null, hit.highlights()));
        }
        List<String> tokens = TrackAnalyzers.tokenize(q);
        String printed;
        try {
            printed = engine.printQuery(q, filters, mustNots);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (engine != index) {
            printed = ElasticsearchCurl.wrap(elasticsearch.settings(), printed);
        }
        SearchResponse response = new SearchResponse(q, tokens, printed, total, List.copyOf(hits), 0);
        if (engine == index) {
            response = withLuceneOverlay(response, request, q, filters, mustNots);
        }
        return withTook(response, start);
    }

    public SearchResponse search(SearchRequest request) throws IOException {
        return search(request, null);
    }

    private TrackSearch engine(String backend) {
        if (isElasticsearch(backend) && elasticsearch.ready()) {
            TrackSearch es = elasticsearch.trackSearch();
            if (es != null) {
                return es;
            }
        }
        return index;
    }

    private SearchResponse withLuceneOverlay(
            SearchResponse response,
            SearchRequest request,
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots) throws IOException {
        Query lucene = TrackLuceneQueryBuilder.buildStructured(q, filters, mustNots);
        List<String> tokens = response.tokens();
        Integer explainDoc = request == null ? null : request.explainDoc();
        IndexSearcher searcher = luceneIndex.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            Map<String, Integer> docsById = luceneDocsById(searcher, response.hits());
            List<SearchHitView> hits = new ArrayList<>();
            for (SearchHitView hit : response.hits()) {
                int luceneDoc = docsById.getOrDefault(hit.id(), hit.luceneDoc());
                hits.add(new SearchHitView(
                        luceneDoc,
                        hit.id(),
                        hit.title(),
                        hit.artist(),
                        hit.genre(),
                        hit.key(),
                        hit.bpm(),
                        hit.rating(),
                        hit.year(),
                        hit.score(),
                        hit.explain(),
                        hit.explainTree(),
                        hit.highlights()));
            }
            if (!hits.isEmpty()) {
                int target = explainDoc == null ? hits.getFirst().luceneDoc() : explainDoc;
                for (int i = 0; i < hits.size(); i++) {
                    SearchHitView hit = hits.get(i);
                    if (hit.luceneDoc() != target) {
                        continue;
                    }
                    Explanation expl = searcher.explain(lucene, hit.luceneDoc());
                    Track track = byId.get(hit.id());
                    if (track != null) {
                        hits.set(i, searchHit(
                                hit.luceneDoc(),
                                track,
                                hit.score(),
                                expl,
                                tokens,
                                hit.highlights()));
                    }
                    break;
                }
            }
            return new SearchResponse(
                    response.q(),
                    tokens,
                    response.query(),
                    response.total(),
                    List.copyOf(hits),
                    response.tookMs());
        }
    }

    private static Map<String, Integer> luceneDocsById(IndexSearcher searcher, List<SearchHitView> hits)
            throws IOException {
        Map<String, Integer> docs = new HashMap<>();
        for (SearchHitView hit : hits) {
            if (hit.id() == null || hit.id().isBlank()) {
                continue;
            }
            TopDocs found = searcher.search(new TermQuery(new Term(TrackDocumentMapper.ID, hit.id())), 1);
            if (found.scoreDocs.length > 0) {
                docs.put(hit.id(), found.scoreDocs[0].doc);
            }
        }
        return docs;
    }

    private static SearchResponse withTook(SearchResponse response, long startNanos) {
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return new SearchResponse(
                response.q(),
                response.tokens(),
                response.query(),
                response.total(),
                response.hits(),
                tookMs);
    }

    private static SearchHitView searchHit(
            int luceneDoc,
            Track track,
            float score,
            Explanation expl,
            List<String> tokens,
            Map<String, String> snippets) {
        return new SearchHitView(
                luceneDoc,
                track.id(),
                track.title(),
                track.artist(),
                track.genre(),
                track.key(),
                track.bpm(),
                track.rating(),
                track.year(),
                score,
                expl == null ? null : expl.toString(),
                expl == null ? null : PlaygroundExplain.from(expl, tokens),
                displayedHighlights(track, snippets));
    }

    private static Map<String, String> displayedHighlights(Track track, Map<String, String> snippets) {
        Map<String, String> fields = new LinkedHashMap<>();
        putHighlight(fields, snippets, TrackDocumentMapper.TITLE, track.title());
        putHighlight(fields, snippets, TrackDocumentMapper.ARTIST, track.artist());
        putHighlight(fields, snippets, TrackDocumentMapper.GENRE, track.genre());
        putHighlight(fields, snippets, TrackDocumentMapper.ALBUM, track.album());
        putHighlight(fields, snippets, TrackDocumentMapper.LABEL, track.label());
        putHighlight(fields, snippets, TrackDocumentMapper.COMMENT, track.comment());
        return Map.copyOf(fields);
    }

    private static void putHighlight(
            Map<String, String> fields, Map<String, String> snippets, String name, String fallback) {
        String snippet = snippets == null ? null : snippets.get(name);
        if (snippet == null || snippet.isBlank()) {
            snippet = fallback == null ? "" : fallback;
        }
        if (!snippet.isBlank()) {
            fields.put(name, snippet);
        }
    }

    public SuggestResponse suggest(String prefix) throws Exception {
        return suggest(prefix, null);
    }

    public SuggestResponse suggest(String prefix, String backend) throws Exception {
        String value = prefix == null ? "" : prefix;
        List<SuggestHitView> hits = new ArrayList<>();
        for (TrackSuggestion suggestion : engine(backend).suggest(value)) {
            hits.add(new SuggestHitView(
                    suggestion.text(),
                    suggestion.field(),
                    bTags(suggestion.highlight())));
        }
        return new SuggestResponse(value, List.copyOf(hits));
    }

    public FacetsResponse facets(String q, String drillGenre) throws Exception {
        return facets(q, drillGenre, null);
    }

    public FacetsResponse facets(String q, String drillGenre, String backend) throws Exception {
        Map<String, List<String>> filters = drillGenre == null || drillGenre.isBlank()
                ? Map.of()
                : Map.of("genre", List.of(drillGenre));
        return facets(q, filters, Map.of(), backend);
    }

    public FacetsResponse facets(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        return facets(q, filters, mustNots, null);
    }

    public FacetsResponse facets(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots, String backend)
            throws Exception {
        TrackSearch engine = engine(backend);
        String queryText = q == null ? "" : q;
        Map<String, List<String>> include = filters == null ? Map.of() : filters;
        Map<String, List<String>> exclude = mustNots == null ? Map.of() : mustNots;
        List<String> genres = include.getOrDefault("genre", List.of()).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        TrackFacetsResult raw = engine.facets(queryText, include, exclude);
        boolean sideways = !genres.isEmpty();
        String printed = engine.printQuery(queryText, include, exclude);
        if (engine != index) {
            printed = ElasticsearchCurl.wrap(elasticsearch.settings(), printed);
        }
        return new FacetsResponse(
                queryText,
                printed,
                sideways,
                String.join(", ", genres),
                List.of(
                        dim("genre", "🏷️", children(raw.genres(), 12)),
                        dim("rating", "⭐", ratings(raw.ratings())),
                        dim("year", "📅", decades(raw.years())),
                        dim("bpm", "⏱", bpmBuckets(raw.bpm())),
                        dim(TrackFacets.KEY, "🎹", camelot(raw.keys()))),
                engine == index ? facetRewrite() : new FacetRewrite(List.of(), List.of()));
    }

    @Override
    public void close() throws IOException {
        try {
            index.close();
        } finally {
            try {
                luceneIndex.close();
            } finally {
                elasticsearch.close();
            }
        }
    }

    private static boolean isElasticsearch(String backend) {
        return backend != null && "elasticsearch".equalsIgnoreCase(backend.trim());
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
                        "added for facets"));
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

    /** Always 1A…12B, including empty slots. */
    private static List<FacetBucket> camelot(Map<String, Long> counts) {
        Map<String, Long> map = counts == null ? Map.of() : counts;
        List<FacetBucket> buckets = new ArrayList<>(TrackFacets.CAMELOT_CODES.size());
        for (String code : TrackFacets.CAMELOT_CODES) {
            buckets.add(new FacetBucket(code, countIgnoreCase(map, code)));
        }
        return List.copyOf(buckets);
    }

    private static List<FacetBucket> children(Map<String, Long> counts, int limit) {
        if (counts == null || counts.isEmpty()) {
            return List.of();
        }
        List<FacetBucket> buckets = new ArrayList<>();
        counts.forEach((label, count) -> {
            if (count != null && count > 0) {
                buckets.add(new FacetBucket(label, count));
            }
        });
        buckets.sort((a, b) -> Long.compare(b.count(), a.count()));
        if (buckets.size() > limit) {
            return List.copyOf(buckets.subList(0, limit));
        }
        return List.copyOf(buckets);
    }

    /** BPM ranges in definition order (0–80 … 220+), skipping empty buckets. */
    private static List<FacetBucket> bpmBuckets(Map<String, Long> counts) {
        Map<String, Long> map = counts == null ? Map.of() : counts;
        List<FacetBucket> buckets = new ArrayList<>();
        for (var range : TrackFacets.bpmRanges()) {
            long count = map.getOrDefault(range.label, 0L);
            if (count > 0) {
                buckets.add(new FacetBucket(range.label, count));
            }
        }
        return List.copyOf(buckets);
    }

    /** Always 5★ → 0★, including empty buckets. */
    private static List<FacetBucket> ratings(Map<String, Long> counts) {
        Map<String, Long> map = counts == null ? Map.of() : counts;
        List<FacetBucket> buckets = new ArrayList<>(6);
        for (int stars = 5; stars >= 0; stars--) {
            String label = Integer.toString(stars);
            buckets.add(new FacetBucket(label, countIgnoreCase(map, label)));
        }
        return List.copyOf(buckets);
    }

    private static List<FacetBucket> decades(Map<String, Long> years) {
        if (years == null || years.isEmpty()) {
            return List.of();
        }
        Map<String, Long> grouped = new HashMap<>();
        years.forEach((label, count) -> {
            if (label == null || count == null || count <= 0) {
                return;
            }
            String decade = TrackFacets.decadeBounds(label) != null
                    ? label
                    : decadeFromRawYear(label);
            if (decade != null) {
                grouped.merge(decade, count, Long::sum);
            }
        });
        return grouped.entrySet().stream()
                .map(entry -> new FacetBucket(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(bucket -> decadeStart(bucket.label())))
                .toList();
    }

    private static String decadeFromRawYear(String label) {
        try {
            return TrackFacets.decadeLabel(Integer.parseInt(label));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int decadeStart(String label) {
        int[] bounds = TrackFacets.decadeBounds(label);
        return bounds == null ? Integer.MAX_VALUE : bounds[0];
    }

    private static long countIgnoreCase(Map<String, Long> counts, String label) {
        Long exact = counts.get(label);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (label.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private static String bTags(String html) {
        if (html == null) {
            return null;
        }
        return html.replace("<em>", "<b>").replace("</em>", "</b>")
                .replace("<EM>", "<b>").replace("</EM>", "</b>");
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
            case TrackDocumentMapper.BPM -> "numeric range / sort";
            case TrackDocumentMapper.RATING, TrackDocumentMapper.YEAR -> "numeric filter / sort";
            default -> "stored field";
        };
    }
}
