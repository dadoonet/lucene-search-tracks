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
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
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
            List<TrackHit> ordered = new ArrayList<>();
            for (ScoreDoc hit : hits.scoreDocs) {
                IndexableField id = searcher.storedFields().document(hit.doc).getField("id");
                if (id == null) {
                    continue;
                }
                Track track = tracks.get(id.stringValue());
                if (track != null) {
                    ordered.add(new TrackHit(track, hit.score));
                }
            }
            return List.copyOf(ordered);
        }
    }

    @Override
    public TrackFacetsResult facets(String q, Map<String, List<String>> postFilters) throws IOException {
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer));
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, FACETS);
            Query base = query(q, Map.of(), Map.of());
            Facets luceneFacets;
            if (postFilters == null || postFilters.isEmpty()) {
                FacetsCollector fc = FacetsCollectorManager.search(
                                searcher, base, 1, new FacetsCollectorManager())
                        .facetsCollector();
                luceneFacets = mix(state, fc);
            } else {
                DrillDownQuery drillDown = new DrillDownQuery(FACETS, base);
                postFilters.forEach((dim, values) ->
                        drillDown.add(dim, query("", Map.of(dim, values), Map.of())));
                luceneFacets = new TrackDrillSideways(searcher, state).search(drillDown, 1).facets;
            }
            FacetResult genres = luceneFacets.getAllChildren("genre");
            FacetResult bpm = luceneFacets.getAllChildren("bpm");
            FacetResult rating = luceneFacets.getAllChildren("rating");
            FacetResult year = luceneFacets.getAllChildren("year");
            return new TrackFacetsResult(
                    toMap(genres),
                    count(bpm, "120 – 130"),
                    toMap(rating),
                    yearRange(year, 2020, 2029));
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
            String luceneField = switch (field) {
                case "title" -> "title.raw.normalized";
                case "artist" -> "artist.raw.normalized";
                case "genre" -> "genre.raw.normalized";
                case "key" -> "key.code";
                default -> null;
            };
            if (luceneField != null) {
                parts.add(new TermQuery(new Term(luceneField, normalize(value == null ? "" : value))));
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

    private static DoubleRange[] bpmRanges() {
        DoubleRange[] ranges = new DoubleRange[16];
        ranges[0] = new DoubleRange("0 – 80", 0.0, true, 80.0, false);
        for (int i = 0; i < 14; i++) {
            double from = 80.0 + (i * 10.0);
            double to = from + 10.0;
            ranges[i + 1] = new DoubleRange(
                    ((int) from) + " – " + ((int) to), from, true, to, false);
        }
        ranges[15] = new DoubleRange("220+", 220.0, true, Double.POSITIVE_INFINITY, false);
        return ranges;
    }

    private static Facets mix(DefaultSortedSetDocValuesReaderState state, FacetsCollector hits)
            throws IOException {
        FacetsCollector collector = hits != null ? hits : new FacetsCollector();
        Map<String, Facets> byDim = new LinkedHashMap<>();
        byDim.put("genre", new SortedSetDocValuesFacetCounts(state, collector));
        byDim.put("bpm", new DoubleRangeFacetCounts("bpm", collector, bpmRanges()));
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

    private static long count(FacetResult result, String label) {
        if (result == null) {
            return 0;
        }
        for (LabelAndValue lv : result.labelValues) {
            if (label.equals(lv.label)) {
                return lv.value.longValue();
            }
        }
        return 0;
    }

    private static long yearRange(FacetResult result, int from, int to) {
        if (result == null) {
            return 0;
        }
        long total = 0;
        for (LabelAndValue lv : result.labelValues) {
            int y = Integer.parseInt(lv.label);
            if (y >= from && y <= to) {
                total += lv.value.longValue();
            }
        }
        return total;
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
