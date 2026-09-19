package fr.pilato.test.lucene;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackFacetsTest {

    @Test
    void bpmRanges_areSixteenHalfOpenBuckets() {
        TrackFacets.NumericRange[] ranges = TrackFacets.bpmRanges();
        assertThat(ranges).hasSize(16);
        assertThat(ranges[0]).isEqualTo(new TrackFacets.NumericRange("0 – 80", 0.0, 80.0));
        assertThat(ranges[5]).isEqualTo(new TrackFacets.NumericRange("120 – 130", 120.0, 130.0));
        assertThat(ranges[15].label()).isEqualTo("220+");
        assertThat(ranges[15].min()).isEqualTo(220.0);
        assertThat(Double.isInfinite(ranges[15].max())).isTrue();
    }

    @Test
    void decadeLabel_skipsJunkYears() {
        assertThat(TrackFacets.decadeLabel(0)).isNull();
        assertThat(TrackFacets.decadeLabel(1899)).isNull();
        assertThat(TrackFacets.decadeLabel(2024)).isEqualTo("2020–2029");
        assertThat(TrackFacets.decadeBounds("2020–2029")).containsExactly(2020, 2029);
        assertThat(TrackFacets.decadeBounds("0–9")).isNull();
    }

    @Test
    void camelot_hasTwentyFourSlots() {
        assertThat(TrackFacets.CAMELOT_CODES).hasSize(24).startsWith("1A", "1B").endsWith("12B");
    }
}
