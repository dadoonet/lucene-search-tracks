package fr.pilato.test.lucene;

import java.util.List;

public final class TrackFacets {
    public static final String GENRE = "genre";
    public static final String KEY = "key";
    public static final int YEAR_MIN = 1900;
    public static final List<String> CAMELOT_CODES = List.of(
            "1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B",
            "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B",
            "9A", "9B", "10A", "10B", "11A", "11B", "12A", "12B");

    public record NumericRange(String label, double min, double max) {}

    private TrackFacets() {}

    public static NumericRange[] bpmRanges() {
        NumericRange[] ranges = new NumericRange[16];
        ranges[0] = new NumericRange("0 – 80", 0.0, 80.0);
        for (int i = 0; i < 14; i++) {
            double from = 80.0 + (i * 10.0);
            double to = from + 10.0;
            ranges[i + 1] = new NumericRange(((int) from) + " – " + ((int) to), from, to);
        }
        ranges[15] = new NumericRange("220+", 220.0, Double.POSITIVE_INFINITY);
        return ranges;
    }

    public static String decadeLabel(int year) {
        if (year < YEAR_MIN) {
            return null;
        }
        int decade = (year / 10) * 10;
        return decade + "–" + (decade + 9);
    }

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
