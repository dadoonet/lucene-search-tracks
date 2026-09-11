package fr.pilato.test.lucene;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.range.DoubleRange;

public final class TrackFacets {
    public static final String GENRE = "genre";
    private static final FacetsConfig CONFIG = new FacetsConfig();

    private TrackFacets() {}

    public static FacetsConfig config() {
        return CONFIG;
    }

    /** 10-BPM buckets. {@code 120 – 130} is [120, 130). */
    public static DoubleRange[] bpmRanges() {
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
}
