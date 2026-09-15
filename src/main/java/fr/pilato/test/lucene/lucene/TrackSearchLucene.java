package fr.pilato.test.lucene.lucene;

import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackFacetsResult;
import fr.pilato.test.lucene.TrackHit;
import fr.pilato.test.lucene.TrackSearch;
import fr.pilato.test.lucene.TrackSuggestion;
import org.apache.lucene.document.Document;
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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TrackSearchLucene implements TrackSearch {

    private static final int SUGGEST_LIMIT = 10;

    private final Directory directory;
    private final IndexWriter writer;
    private final Directory suggestionDirectory;
    private final AnalyzingInfixSuggester suggester;
    private final Object writeLock = new Object();
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    public TrackSearchLucene() throws IOException {
        directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(TrackAnalyzers.searchAnalyzer());
        writer = new IndexWriter(directory, config);
        suggestionDirectory = new ByteBuffersDirectory();
        suggester = new AnalyzingInfixSuggester(
                suggestionDirectory, TrackAnalyzers.searchAnalyzer());
    }

    @Override
    public void rebuild(List<Track> tracks) throws IOException {
        synchronized (writeLock) {
            writer.deleteAll();
            for (Track track : tracks) {
                writer.addDocument(indexedDocument(track));
            }
            writer.commit();
            this.tracks.clear();
            for (Track track : tracks) {
                this.tracks.put(track.id(), track);
            }
            rebuildSuggester();
        }
    }

    @Override
    public List<TrackHit> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws IOException {
        var lucene = TrackLuceneQueryBuilder.buildStructured(q, filters, mustNots);
        IndexSearcher searcher = searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            return TrackLuceneQueryBuilder.searchHits(searcher, lucene, List.copyOf(tracks.values()));
        }
    }

    @Override
    public TrackFacetsResult facets(String q, Map<String, List<String>> postFilters) throws IOException {
        IndexSearcher searcher = searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, TrackFacets.config());
            Query base = TrackLuceneQueryBuilder.buildStructured(q, Map.of(), Map.of());
            Facets luceneFacets;
            if (postFilters == null || postFilters.isEmpty()) {
                FacetsCollector fc = FacetsCollectorManager.search(
                                searcher, base, 1, new FacetsCollectorManager())
                        .facetsCollector();
                luceneFacets = mix(state, fc);
            } else {
                DrillDownQuery drillDown = new DrillDownQuery(TrackFacets.config(), base);
                postFilters.forEach((dim, values) -> {
                    Query clause = TrackLuceneQueryBuilder.buildStructured("", Map.of(dim, values), Map.of());
                    drillDown.add(dim, clause);
                });
                luceneFacets = new TrackDrillSideways(searcher, state).search(drillDown, 1).facets;
            }
            return toResult(luceneFacets);
        }
    }

    @Override
    public List<TrackSuggestion> suggest(String prefix) throws IOException {
        return suggest(prefix, null);
    }

    @Override
    public List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws IOException {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        if (scope != null && scope.isEmpty()) {
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

    public IndexSearcher searcher() throws IOException {
        return new IndexSearcher(DirectoryReader.open(writer));
    }

    public int numDocs() {
        synchronized (writeLock) {
            return writer.getDocStats().numDocs;
        }
    }

    public long ramBytesUsed() throws IOException {
        synchronized (writeLock) {
            return sizeOf(directory) + sizeOf(suggestionDirectory);
        }
    }

    private static long sizeOf(Directory directory) throws IOException {
        long bytes = 0L;
        for (String name : directory.listAll()) {
            bytes += directory.fileLength(name);
        }
        return bytes;
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

    private static Document indexedDocument(Track track) throws IOException {
        return TrackFacets.config().build(TrackDocumentMapper.toDocument(track));
    }

    void rebuildSuggester() throws IOException {
        suggester.build(new TrackSuggestionInputIterator(tracks.values()));
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
        return suggestionKey(suggestion.field(), suggestion.text());
    }

    private static String suggestionKey(String field, String text) {
        return field + "\0" + text;
    }

    private static TrackFacetsResult toResult(Facets facets) throws IOException {
        FacetResult genres = facets.getAllChildren(TrackFacets.GENRE);
        FacetResult bpm = facets.getAllChildren(TrackDocumentMapper.BPM);
        FacetResult rating = facets.getAllChildren(TrackDocumentMapper.RATING);
        FacetResult year = facets.getAllChildren(TrackDocumentMapper.YEAR);
        return new TrackFacetsResult(
                toMap(genres),
                count(bpm, "120 – 130"),
                toMap(rating),
                yearRange(year, 2020, 2029));
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

    private static final class TrackDrillSideways extends DrillSideways {
        private final DefaultSortedSetDocValuesReaderState ssdvState;

        private TrackDrillSideways(IndexSearcher searcher, DefaultSortedSetDocValuesReaderState state) {
            super(searcher, TrackFacets.config(), state);
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

    private static final class TrackSuggestionInputIterator implements InputIterator {
        private final Iterator<TrackSuggestion> values;
        private TrackSuggestion current;

        private TrackSuggestionInputIterator(Iterable<Track> tracks) {
            Map<String, TrackSuggestion> distinct = new LinkedHashMap<>();
            for (Track track : tracks) {
                for (TrackSuggestion suggestion : suggestionsFor(track)) {
                    distinct.putIfAbsent(suggestionKey(suggestion), suggestion);
                }
            }
            values = distinct.values().iterator();
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
