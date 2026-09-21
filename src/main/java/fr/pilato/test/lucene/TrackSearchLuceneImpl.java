package fr.pilato.test.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.LongValueFacetCounts;
import org.apache.lucene.facet.MultiFacets;
import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.WholeBreakIterator;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Self-contained Lucene {@link TrackSearch}: same public surface as
 * {@link TrackSearchElasticsearchImpl}, with
 * analyzer / mapping / query / facets / suggest inlined. Playground uses this
 * for the {@link TrackSearch} contract.
 */
public final class TrackSearchLuceneImpl implements TrackSearch {

    private static final int SUGGEST_LIMIT = 10;
    private static final float TITLE_BOOST = 4.0f;
    private static final float ARTIST_BOOST = 3.0f;
    private static final float GENRE_BOOST = 2.0f;
    private static final float ALBUM_BOOST = 1.5f;
    private static final float LABEL_BOOST = 1.0f;
    private static final float COMMENT_BOOST = 0.5f;
    private static final float PREFIX_BOOST = 0.25f;
    private static final String[] HIGHLIGHT_FIELDS = {"title", "artist", "genre"};
    private static final FacetsConfig FACETS = new FacetsConfig();

    private final Directory directory;
    private final IndexWriter writer;
    private final Directory suggestionDirectory;
    private final AnalyzingInfixSuggester suggester;
    private final Object writeLock = new Object();
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    public TrackSearchLuceneImpl() throws IOException {
        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer()));
        suggestionDirectory = new ByteBuffersDirectory();
        suggester = new AnalyzingInfixSuggester(suggestionDirectory, analyzer());
    }

    @Override
    public void rebuild(List<Track> tracks) throws IOException {
        synchronized (writeLock) {
            writer.deleteAll();
            for (Track track : tracks) {
                writer.addDocument(FACETS.build(toDocument(track)));
            }
            writer.commit();
            this.tracks.clear();
            for (Track track : tracks) {
                this.tracks.put(track.id(), track);
            }
            Map<String, TrackSuggestion> distinct = new LinkedHashMap<>();
            for (Track track : tracks) {
                for (TrackSuggestion suggestion : suggestionsFor(track)) {
                    distinct.putIfAbsent(suggestionKey(suggestion), suggestion);
                }
            }
            suggester.build(new SuggestionIterator(distinct.values().iterator()));
        }
    }

    @Override
    public List<TrackHit> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws IOException {
        Query lucene = query(q, filters, mustNots);
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer));
        try (IndexReader reader = searcher.getIndexReader()) {
            int limit = Math.max(1, reader.numDocs());
            TopDocs hits = searcher.search(lucene, limit);
            List<Map<String, String>> highlighted = highlight(searcher, lucene, hits);
            List<TrackHit> ordered = new ArrayList<>();
            for (int i = 0; i < hits.scoreDocs.length; i++) {
                ScoreDoc hit = hits.scoreDocs[i];
                IndexableField id = searcher.storedFields().document(hit.doc).getField("id");
                if (id == null) {
                    continue;
                }
                Track track = tracks.get(id.stringValue());
                if (track != null) {
                    Map<String, String> snippets =
                            i < highlighted.size() ? highlighted.get(i) : Map.of();
                    ordered.add(new TrackHit(track, hit.score, snippets));
                }
            }
            return List.copyOf(ordered);
        }
    }

    private static List<Map<String, String>> highlight(
            IndexSearcher searcher, Query query, TopDocs topDocs) throws IOException {
        if (topDocs == null || topDocs.scoreDocs.length == 0) {
            return List.of();
        }
        try (Analyzer analyzer = analyzer()) {
            UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                    .withMaxLength(10_000)
                    .withBreakIterator(WholeBreakIterator::new)
                    .build();
            Map<String, String[]> byField = highlighter.highlightFields(HIGHLIGHT_FIELDS, query, topDocs);
            List<Map<String, String>> hits = new ArrayList<>(topDocs.scoreDocs.length);
            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String field : HIGHLIGHT_FIELDS) {
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

    @Override
    public TrackFacetsResult facets(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws IOException {
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer));
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, FACETS);
            Map<String, List<String>> drill = new LinkedHashMap<>();
            Map<String, List<String>> baseFilters = new LinkedHashMap<>();
            if (filters != null) {
                for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
                    if (TrackFacets.GENRE.equals(entry.getKey()) || TrackFacets.KEY.equals(entry.getKey())) {
                        if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                            drill.put(entry.getKey(), entry.getValue());
                        }
                    } else {
                        baseFilters.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            Query base = query(q, baseFilters, mustNots == null ? Map.of() : mustNots);
            Facets luceneFacets;
            if (drill.isEmpty()) {
                FacetsCollector fc = FacetsCollectorManager.search(
                                searcher, base, 1, new FacetsCollectorManager())
                        .facetsCollector();
                luceneFacets = mix(state, fc);
            } else {
                DrillDownQuery drillDown = new DrillDownQuery(FACETS, base);
                drill.forEach((dim, values) ->
                        drillDown.add(dim, query("", Map.of(dim, values), Map.of())));
                luceneFacets = new TrackDrillSideways(searcher, state).search(drillDown, 1).facets;
            }
            FacetResult genres = luceneFacets.getAllChildren(TrackFacets.GENRE);
            FacetResult bpm = luceneFacets.getAllChildren("bpm");
            FacetResult rating = luceneFacets.getAllChildren("rating");
            FacetResult year = luceneFacets.getAllChildren("year");
            FacetResult keys = luceneFacets.getAllChildren(TrackFacets.KEY);
            return new TrackFacetsResult(
                    toMap(genres),
                    toMap(bpm),
                    toMap(rating),
                    decades(year),
                    toMap(keys));
        }
    }

    @Override
    public List<TrackSuggestion> suggest(String prefix) throws IOException {
        return suggest(prefix, null);
    }

    @Override
    public List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws IOException {
        if (prefix == null || prefix.isBlank() || (scope != null && scope.isEmpty())) {
            return List.of();
        }
        Set<String> allowed = null;
        if (scope != null) {
            allowed = new HashSet<>();
            for (Track track : scope) {
                for (TrackSuggestion suggestion : suggestionsFor(track)) {
                    allowed.add(suggestionKey(suggestion));
                }
            }
            if (allowed.isEmpty()) {
                return List.of();
            }
        }
        synchronized (writeLock) {
            int lookupCount = SUGGEST_LIMIT;
            if (allowed != null) {
                lookupCount = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(SUGGEST_LIMIT, suggester.getCount()));
            }
            List<Lookup.LookupResult> matches =
                    suggester.lookup(prefix, Set.of(), lookupCount, true, true);
            List<TrackSuggestion> result = new ArrayList<>(SUGGEST_LIMIT);
            for (Lookup.LookupResult match : matches) {
                String field = match.payload != null ? match.payload.utf8ToString() : "";
                String text = match.key.toString();
                if (allowed != null && !allowed.contains(suggestionKey(field, text))) {
                    continue;
                }
                String highlight = match.highlightKey != null ? match.highlightKey.toString() : text;
                result.add(new TrackSuggestion(text, field, highlight));
                if (result.size() >= SUGGEST_LIMIT) {
                    break;
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            suggester.close();
            suggestionDirectory.close();
            writer.close();
            directory.close();
        }
    }

    private Query query(String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots) {
        Query text = analyzedFreeText(q);
        boolean hasText = !(text instanceof MatchAllDocsQuery);
        BooleanQuery.Builder result = new BooleanQuery.Builder();
        if (hasText) {
            result.add(text, BooleanClause.Occur.MUST);
        }
        int filterCount = addClauses(result, filters, BooleanClause.Occur.FILTER);
        int notCount = addClauses(result, mustNots, BooleanClause.Occur.MUST_NOT);
        if (!hasText && filterCount == 0 && notCount == 0) {
            return MatchAllDocsQuery.INSTANCE;
        }
        if (!hasText && filterCount == 0 && notCount > 0) {
            result.add(MatchAllDocsQuery.INSTANCE, BooleanClause.Occur.MUST);
        }
        if (hasText && filterCount == 0 && notCount == 0) {
            return text;
        }
        return result.build();
    }

    private static int addClauses(
            BooleanQuery.Builder result, Map<String, List<String>> clauses, BooleanClause.Occur occur) {
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
            return MatchAllDocsQuery.INSTANCE;
        }
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return MatchAllDocsQuery.INSTANCE;
        }
        List<Query> tokenQueries = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            tokenQueries.add(freeTextQuery(tokens.get(i), i == tokens.size() - 1));
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
        addFreeTextField(fields, "title", TITLE_BOOST, token, prefix);
        addFreeTextField(fields, "artist", ARTIST_BOOST, token, prefix);
        addFreeTextField(fields, "genre", GENRE_BOOST, token, prefix);
        addFreeTextField(fields, "album", ALBUM_BOOST, token, prefix);
        addFreeTextField(fields, "label", LABEL_BOOST, token, prefix);
        addFreeTextField(fields, "comment", COMMENT_BOOST, token, prefix);
        fields.setMinimumNumberShouldMatch(1);
        return fields.build();
    }

    private static void addFreeTextField(
            BooleanQuery.Builder fields, String field, float boost, String token, boolean prefix) {
        fields.add(new BoostQuery(new TermQuery(new Term(field, token)), boost), BooleanClause.Occur.SHOULD);
        if (prefix && !token.isEmpty()) {
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
        for (Query part : parts) {
            sameField.add(part, BooleanClause.Occur.SHOULD);
        }
        sameField.setMinimumNumberShouldMatch(1);
        return sameField.build();
    }

    private static Query fieldQuery(String field, String value) {
        return switch (field) {
            case "title" -> new TermQuery(new Term("title.raw.normalized", normalize(value)));
            case "artist" -> new TermQuery(new Term("artist.raw.normalized", normalize(value)));
            case "genre" -> new TermQuery(new Term("genre.raw.normalized", normalize(value)));
            case "key" -> new TermQuery(new Term("key.code", normalize(value)));
            case "bpm" -> bpmRange(value);
            case "rating" -> ratingExact(value);
            case "year" -> yearDecade(value);
            default -> null;
        };
    }

    private static Query bpmRange(String label) {
        for (TrackFacets.NumericRange range : TrackFacets.bpmRanges()) {
            if (range.label().equals(label)) {
                double max = Double.isInfinite(range.max())
                        ? Double.POSITIVE_INFINITY
                        : Math.nextDown(range.max());
                return DoubleField.newRangeQuery("bpm", range.min(), max);
            }
        }
        return null;
    }

    private static Query ratingExact(String label) {
        try {
            return IntField.newExactQuery("rating", Integer.parseInt(label.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Query yearDecade(String label) {
        int[] bounds = TrackFacets.decadeBounds(label);
        if (bounds == null) {
            return new MatchNoDocsQuery();
        }
        return IntField.newRangeQuery("year", bounds[0], bounds[1]);
    }

    private static Document toDocument(Track t) {
        Document doc = new Document();
        doc.add(new StringField("id", t.id(), Field.Store.YES));
        addText(doc, "title", t.title());
        addKeyword(doc, "title.raw", "title.raw.normalized", t.title());
        addText(doc, "artist", t.artist());
        addKeyword(doc, "artist.raw", "artist.raw.normalized", t.artist());
        addText(doc, "genre", t.genre());
        addKeyword(doc, "genre.raw", "genre.raw.normalized", t.genre());
        String genre = nfc(t.genre());
        if (!genre.isEmpty()) {
            doc.add(new SortedSetDocValuesFacetField("genre", genre));
        }
        String key = t.key();
        if (key != null && !key.isBlank()) {
            doc.add(new SortedSetDocValuesFacetField("key", key));
        }
        addText(doc, "album", t.album());
        addText(doc, "label", t.label());
        addText(doc, "comment", t.comment());
        doc.add(new StringField("key.code", normalize(t.key()), Field.Store.YES));
        doc.add(new DoubleField("bpm", t.bpm(), Field.Store.YES));
        doc.add(new IntField("rating", t.rating(), Field.Store.YES));
        doc.add(new IntField("year", t.year(), Field.Store.YES));
        return doc;
    }

    private static void addText(Document doc, String field, String value) {
        doc.add(new TextField(field, nfc(value), Field.Store.YES));
    }

    private static void addKeyword(Document doc, String raw, String normalized, String value) {
        String nfc = nfc(value);
        doc.add(new StringField(raw, nfc, Field.Store.YES));
        doc.add(new StringField(normalized, normalize(nfc), Field.Store.YES));
    }

    private static String nfc(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static String normalize(String s) {
        return nfc(s).toLowerCase(Locale.ROOT);
    }

    private static Analyzer analyzer() {
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

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        try (Analyzer analyzer = analyzer();
             TokenStream stream = analyzer.tokenStream("title", text)) {
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

    private static Facets mix(DefaultSortedSetDocValuesReaderState state, FacetsCollector hits)
            throws IOException {
        FacetsCollector collector = hits != null ? hits : new FacetsCollector();
        Map<String, Facets> byDim = new LinkedHashMap<>();
        byDim.put("genre", new SortedSetDocValuesFacetCounts(state, collector));
        byDim.put("key", new SortedSetDocValuesFacetCounts(state, collector));
        TrackFacets.NumericRange[] src = TrackFacets.bpmRanges();
        DoubleRange[] ranges = new DoubleRange[src.length];
        for (int i = 0; i < src.length; i++) {
            TrackFacets.NumericRange range = src[i];
            ranges[i] = new DoubleRange(range.label(), range.min(), true, range.max(), false);
        }
        byDim.put("bpm", new DoubleRangeFacetCounts("bpm", collector, ranges));
        byDim.put("rating", new LongValueFacetCounts("rating", collector));
        byDim.put("year", new LongValueFacetCounts("year", collector));
        return new MultiFacets(byDim);
    }

    private static Map<String, Long> toMap(FacetResult result) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (result == null) {
            return out;
        }
        for (LabelAndValue lv : result.labelValues) {
            out.put(lv.label, lv.value.longValue());
        }
        return out;
    }

    private static Map<String, Long> decades(FacetResult year) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (year == null) {
            return out;
        }
        for (LabelAndValue lv : year.labelValues) {
            String label = TrackFacets.decadeLabel(Integer.parseInt(lv.label));
            if (label != null) {
                out.merge(label, lv.value.longValue(), Long::sum);
            }
        }
        return out;
    }

    private static List<TrackSuggestion> suggestionsFor(Track track) {
        List<TrackSuggestion> values = new ArrayList<>(3);
        addIfPresent(values, "title", track.title());
        addIfPresent(values, "artist", track.artist());
        addIfPresent(values, "genre", track.genre());
        return values;
    }

    private static void addIfPresent(List<TrackSuggestion> values, String field, String text) {
        if (text != null && !text.isBlank()) {
            values.add(new TrackSuggestion(text, field));
        }
    }

    private static String suggestionKey(TrackSuggestion suggestion) {
        return suggestion.field() + "\0" + suggestion.text();
    }

    private static String suggestionKey(String field, String text) {
        return field + "\0" + text;
    }

    private static final class TrackDrillSideways extends DrillSideways {
        private final DefaultSortedSetDocValuesReaderState ssdvState;

        private TrackDrillSideways(IndexSearcher searcher, DefaultSortedSetDocValuesReaderState state) {
            super(searcher, FACETS, state);
            this.ssdvState = state;
        }

        @Override
        protected Facets buildFacetsResult(
                FacetsCollector drillDowns,
                FacetsCollector[] drillSideways,
                String[] drillSidewaysDims) throws IOException {
            Facets drillDownFacets = mix(ssdvState, drillDowns);
            if (drillSideways == null || drillSideways.length == 0) {
                return drillDownFacets;
            }
            Map<String, Facets> sideways = new LinkedHashMap<>();
            for (int i = 0; i < drillSideways.length; i++) {
                sideways.put(drillSidewaysDims[i], mix(ssdvState, drillSideways[i]));
            }
            return new MultiFacets(sideways, drillDownFacets);
        }
    }

    private static final class SuggestionIterator implements InputIterator {
        private final Iterator<TrackSuggestion> values;
        private TrackSuggestion current;

        private SuggestionIterator(Iterator<TrackSuggestion> values) {
            this.values = values;
        }

        @Override
        public BytesRef next() {
            if (!values.hasNext()) {
                current = null;
                return null;
            }
            current = values.next();
            return new BytesRef(current.text());
        }

        @Override
        public long weight() {
            return 1;
        }

        @Override
        public BytesRef payload() {
            return new BytesRef(current.field());
        }

        @Override
        public boolean hasPayloads() {
            return true;
        }

        @Override
        public Set<BytesRef> contexts() {
            return Set.of();
        }

        @Override
        public boolean hasContexts() {
            return false;
        }
    }
}
