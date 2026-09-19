package fr.pilato.test.lucene.playground.helpers;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.range.DoubleRange;

import java.util.List;

public final class TrackFacets {
    public static final String GENRE = fr.pilato.test.lucene.TrackFacets.GENRE;
    public static final String KEY = fr.pilato.test.lucene.TrackFacets.KEY;
    /** Rekordbox missing years are 0; junk values like 1 are not real decades. */
    public static final int YEAR_MIN = fr.pilato.test.lucene.TrackFacets.YEAR_MIN;
    /** Inner ring A (minor), outer ring B (major), 1 at 12 o'clock then clockwise. */
    public static final List<String> CAMELOT_CODES = fr.pilato.test.lucene.TrackFacets.CAMELOT_CODES;
    private static final FacetsConfig CONFIG = new FacetsConfig();

    private TrackFacets() {}

    public static FacetsConfig config() {
        return CONFIG;
    }

    /** 10-BPM buckets. {@code 120 – 130} is [120, 130). */
    public static DoubleRange[] bpmRanges() {
        var src = fr.pilato.test.lucene.TrackFacets.bpmRanges();
        DoubleRange[] ranges = new DoubleRange[src.length];
        for (int i = 0; i < src.length; i++) {
            var r = src[i];
            ranges[i] = new DoubleRange(r.label(), r.min(), true, r.max(), false);
        }
        return ranges;
    }

    /** Decade label {@code 2020–2029}, or {@code null} when {@code year} is missing/junk. */
    public static String decadeLabel(int year) {
        return fr.pilato.test.lucene.TrackFacets.decadeLabel(year);
    }

    /**
     * Inclusive {@code [from, to]} for a decade chip, or {@code null} when the label is not a
     * plausible music decade.
     */
    public static int[] decadeBounds(String label) {
        return fr.pilato.test.lucene.TrackFacets.decadeBounds(label);
    }
}
