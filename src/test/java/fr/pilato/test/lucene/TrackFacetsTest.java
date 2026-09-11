package fr.pilato.test.lucene;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackFacetsTest {

    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        index = new TrackSearchIndex();
        index.rebuild(TrackDataset.load());
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
                    TrackIndexFields.BPM, fc, TrackFacets.bpmRanges());
            Facets rating = new LongValueFacetCounts(TrackIndexFields.RATING, fc);
            Facets year = new LongValueFacetCounts(TrackIndexFields.YEAR, fc);

            assertThat(count(genres.getAllChildren("genre"), "Club")).isEqualTo(26);
            assertThat(count(bpm.getAllChildren(TrackIndexFields.BPM), "120 – 130")).isEqualTo(52);
            assertThat(count(rating.getAllChildren(TrackIndexFields.RATING), "5")).isEqualTo(13);
            long twenties = 0;
            for (LabelAndValue lv : year.getAllChildren(TrackIndexFields.YEAR).labelValues) {
                int y = Integer.parseInt(lv.label);
                if (y >= 2020 && y <= 2029) {
                    twenties += lv.value.longValue();
                }
            }
            assertThat(twenties).isEqualTo(15);
        }
    }

    @Test
    void drillSideways_keepsOtherGenres() throws Exception {
        Query base = TrackLuceneQueryBuilder.buildStructured("Bob", Map.of(), Map.of());
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, TrackFacets.config());
            DrillDownQuery drillDown = new DrillDownQuery(TrackFacets.config(), base);
            drillDown.add("genre", new TermQuery(new Term(TrackIndexFields.GENRE_RAW, "Club")));
            Facets facets = new TrackDrillSideways(searcher, state)
                    .search(drillDown, 1)
                    .facets;
            assertThat(count(facets.getAllChildren("genre"), "Dance")).isGreaterThan(0);
            assertThat(count(facets.getAllChildren(TrackIndexFields.BPM), "120 – 130"))
                    .isLessThan(52);
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
            byDim.put(TrackIndexFields.BPM, new DoubleRangeFacetCounts(
                    TrackIndexFields.BPM, collector, TrackFacets.bpmRanges()));
            return new MultiFacets(byDim);
        }
    }
}
