package fr.pilato.test.lucene.playground.helpers;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.range.DoubleRange;

import java.util.List;

public final class TrackFacets {
    public static final String GENRE = "genre";
    public static final String KEY = "key";
    /** Rekordbox missing years are 0; junk values like 1 are not real decades. */
    public static final int YEAR_MIN = 1900;
    /** Inner ring A (minor), outer ring B (major), 1 at 12 o'clock then clockwise. */
    public static final List<String> CAMELOT_CODES = List.of(
            "1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B",
            "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B",
            "9A", "9B", "10A", "10B", "11A", "11B", "12A", "12B");
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

    /** Decade label {@code 2020–2029}, or {@code null} when {@code year} is missing/junk. */
    public static String decadeLabel(int year) {
        if (year < YEAR_MIN) {
            return null;
        }
        int decade = (year / 10) * 10;
        return decade + "–" + (decade + 9);
    }

    /**
     * Inclusive {@code [from, to]} for a decade chip, or {@code null} when the label is not a
     * plausible music decade.
     */
    public static int[] decadeBounds(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String[] parts = label.split("–", 2);
        if (parts.length != 2) {
            parts = label.split("-", 2);
        }
        if (parts.length != 2) {
            return null;
        }
        try {
            int from = Integer.parseInt(parts[0].trim());
            int to = Integer.parseInt(parts[1].trim());
            if (from < YEAR_MIN || to < from) {
                return null;
            }
            return new int[] {from, to};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
