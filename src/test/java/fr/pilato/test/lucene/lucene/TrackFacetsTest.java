package fr.pilato.test.lucene.lucene;

import fr.pilato.test.lucene.TrackDatasetLoader;
import fr.pilato.test.lucene.TrackTestLog;
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
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackFacetsTest {

    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        index = new TrackSearchIndex();
        index.rebuild(TrackDatasetLoader.load());
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void bob_countsGenreBpmRatingYear() throws Exception {
        Query lucene = TrackLuceneQueryBuilder.buildStructured("Bob", Map.of(), Map.of());
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, TrackFacets.config());
            FacetsCollector fc = FacetsCollectorManager.search(
                            searcher, lucene, 1, new FacetsCollectorManager())
                    .facetsCollector();
            Facets genres = new SortedSetDocValuesFacetCounts(state, fc);
            Facets bpm = new DoubleRangeFacetCounts(
                    TrackDocumentMapper.BPM, fc, TrackFacets.bpmRanges());
            Facets rating = new LongValueFacetCounts(TrackDocumentMapper.RATING, fc);
            Facets year = new LongValueFacetCounts(TrackDocumentMapper.YEAR, fc);

            assertThat(count(genres.getAllChildren("genre"), "Club")).isEqualTo(26);
            assertThat(count(bpm.getAllChildren(TrackDocumentMapper.BPM), "120 – 130")).isEqualTo(52);
            assertThat(count(rating.getAllChildren(TrackDocumentMapper.RATING), "5")).isEqualTo(13);
            long twenties = 0;
            for (LabelAndValue lv : year.getAllChildren(TrackDocumentMapper.YEAR).labelValues) {
                int y = Integer.parseInt(lv.label);
                if (y >= 2020 && y <= 2029) {
                    twenties += lv.value.longValue();
                }
            }
            assertThat(twenties).isEqualTo(15);
            TrackTestLog.facets("Bob", List.of(
                    new TrackTestLog.FacetLine("🏷️", "Club", 26),
                    new TrackTestLog.FacetLine("⏱", "120 – 130", 52),
                    new TrackTestLog.FacetLine("⭐", "5", 13),
                    new TrackTestLog.FacetLine("📅", "2020–2029", twenties)));
            TrackTestLog.facetChildren("genre", genres.getAllChildren("genre").labelValues, 8);
        }
    }

    @Test
    void drillSideways_keepsOtherGenres() throws Exception {
        Query base = TrackLuceneQueryBuilder.buildStructured("Bob", Map.of(), Map.of());
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, TrackFacets.config());
            DrillDownQuery drillDown = new DrillDownQuery(TrackFacets.config(), base);
            drillDown.add("genre", new TermQuery(new Term(TrackDocumentMapper.GENRE_RAW, "Club")));
            Facets facets = new TrackDrillSideways(searcher, state)
                    .search(drillDown, 1)
                    .facets;
            assertThat(count(facets.getAllChildren("genre"), "Dance")).isGreaterThan(0);
            assertThat(count(facets.getAllChildren(TrackDocumentMapper.BPM), "120 – 130"))
                    .isLessThan(52);
            TrackTestLog.drillSideways(
                    "Bob",
                    "genre",
                    "Club",
                    count(facets.getAllChildren("genre"), "Dance"),
                    count(facets.getAllChildren(TrackDocumentMapper.BPM), "120 – 130"));
        }
    }

    private static int count(FacetResult result, String label) {
        if (result == null) {
            return 0;
        }
        for (LabelAndValue lv : result.labelValues) {
            if (label.equals(lv.label)) {
                return lv.value.intValue();
            }
        }
        return 0;
    }

    private static final class TrackDrillSideways extends DrillSideways {
        private TrackDrillSideways(IndexSearcher searcher, DefaultSortedSetDocValuesReaderState state) {
            super(searcher, TrackFacets.config(), state);
        }

        @Override
        protected Facets buildFacetsResult(
                FacetsCollector drillDowns,
                FacetsCollector[] drillSideways,
                String[] drillSidewaysDims) throws IOException {
            Facets drillDownFacets = mix(drillDowns);
            if (drillSideways == null || drillSideways.length == 0) {
                return drillDownFacets;
            }
            Map<String, Facets> sideways = new LinkedHashMap<>();
            for (int i = 0; i < drillSideways.length; i++) {
                sideways.put(drillSidewaysDims[i], mix(drillSideways[i]));
            }
            return new MultiFacets(sideways, drillDownFacets);
        }

        private Facets mix(FacetsCollector hits) throws IOException {
            FacetsCollector collector = hits != null ? hits : new FacetsCollector();
            Map<String, Facets> byDim = new LinkedHashMap<>();
            Facets ssdv = new SortedSetDocValuesFacetCounts(state, collector);
            byDim.put(TrackFacets.GENRE, ssdv);
            byDim.put(TrackDocumentMapper.BPM, new DoubleRangeFacetCounts(
                    TrackDocumentMapper.BPM, collector, TrackFacets.bpmRanges()));
            return new MultiFacets(byDim);
        }
    }
}
