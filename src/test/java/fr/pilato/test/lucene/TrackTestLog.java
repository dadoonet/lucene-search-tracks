package fr.pilato.test.lucene;

import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.search.Query;

import java.lang.System.Logger;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Emoji-prefixed narrative of what a test did and what Lucene returned.
 * Logs through {@link System.Logger} and stdout so {@code mvn test} prints the story.
 */
public final class TrackTestLog {

    public static final int TOP_HITS = 8;

    private static final Logger LOG = System.getLogger(TrackTestLog.class.getName());

    static {
        var jul = java.util.logging.Logger.getLogger(TrackTestLog.class.getName());
        jul.setUseParentHandlers(false);
    }

    private TrackTestLog() {}

    public static void search(
            String q,
            Map<String, List<String>> filters,
            Map<String, List<String>> mustNots,
            List<TrackHit> hits) {
        info("");
        StringBuilder header = new StringBuilder("🔎 searching for ");
        if (q == null || q.isBlank()) {
            header.append("(empty q)");
        } else {
            header.append('"').append(q).append('"');
        }
        if (filters != null && !filters.isEmpty()) {
            header.append(" with filter on ").append(chips(filters));
        }
        info(header.toString());
        if (mustNots != null && !mustNots.isEmpty()) {
            info("🚫 " + chips(mustNots));
        }
        if (hits == null || hits.isEmpty()) {
            info("🫙 no hits");
            return;
        }
        int shown = Math.min(TOP_HITS, hits.size());
        String summary = "📊 " + hits.size() + " hits";
        if (hits.size() > TOP_HITS) {
            summary += " — showing top " + TOP_HITS;
        }
        info(summary);
        for (int i = 0; i < shown; i++) {
            TrackHit hit = hits.get(i);
            Track t = hit.track();
            info(String.format(
                    Locale.ROOT,
                    "   %d. %6.2f  🎤 %s — 💿 %s  ·  🏷️ %s  🎹 %s  ⏱ %s  %s",
                    i + 1,
                    hit.score(),
                    dash(t.artist()),
                    dash(t.title()),
                    dash(t.genre()),
                    dash(t.key()),
                    formatBpm(t.bpm()),
                    stars(t.rating())));
        }
        int remaining = hits.size() - shown;
        if (remaining > 0) {
            info("   … and " + remaining + " more");
        }
    }

    public static void suggest(String prefix, Collection<Track> scope, List<TrackSuggestion> hits) {
        String where = scope == null
                ? "whole dictionary"
                : (scope.isEmpty() ? "empty scope" : scope.size() + " tracks");
        info("");
        info("💡 suggest \"" + prefix + "\"  ·  " + where);
        if (hits == null || hits.isEmpty()) {
            info("🫙 no suggestions");
            return;
        }
        for (TrackSuggestion hit : hits) {
            String emoji = switch (hit.field()) {
                case "artist" -> "🎤";
                case "title" -> "💿";
                case "genre" -> "🏷️";
                default -> "📎";
            };
            info("   " + emoji + " " + hit.text() + "  ·  " + hit.field() + "  ·  " + hit.highlight());
        }
    }

    public static void dataset(List<Track> tracks, Track reference) {
        info("");
        info("📦 loaded " + tracks.size() + " tracks from tracks.ndjson");
        info("   🎯 " + reference.artist() + " — " + reference.title()
                + "  ·  id=" + reference.id()
                + "  ·  🏷️ " + reference.genre()
                + "  🎹 " + reference.key()
                + "  ⏱ " + formatBpm(reference.bpm())
                + "  " + stars(reference.rating()));
    }

    public static void mapping(Track track, List<String> titleTokens, List<String> artistTokens) {
        info("");
        info("🗺️ mapping " + track.artist() + " — " + track.title());
        info("   🔤 title  " + titleTokens);
        info("   🔤 artist " + artistTokens);
        info("   🏷️ genre=" + track.genre()
                + "  🎹 key=" + track.key()
                + "  ⏱ " + formatBpm(track.bpm())
                + "  " + stars(track.rating()));
    }

    public static void indexRebuilt(int corpusSize, int numDocs) {
        info("");
        String match = corpusSize == numDocs ? " (matches corpus)" : "";
        info("💾 rebuilt RAM index  ·  " + numDocs + " docs" + match);
    }

    public static void luceneQuery(Query query) {
        info("");
        info("🧬 " + query);
    }

    public static void facets(String q, List<FacetLine> lines) {
        info("");
        info("📊 facets under q=\"" + q + "\"");
        for (FacetLine line : lines) {
            info(String.format(Locale.ROOT, "   %s  %-16s  %d", line.emoji(), line.label(), line.count()));
        }
    }

    public static void drillSideways(String q, String dim, String value, long otherGenreCount, long bpmBucket) {
        info("");
        info("📐 DrillSideways  q=\"" + q + "\"  drill-down 🏷️ \"" + dim + ":" + value + "\"");
        info("   🏷️ other genres still visible (e.g. Dance=" + otherGenreCount + ")");
        info("   ⏱  120 – 130  →  " + bpmBucket + "  (narrower than unfiltered 52)");
    }

    public static void facetChildren(String dim, LabelAndValue[] values, int limit) {
        if (values == null) {
            return;
        }
        int shown = Math.min(limit, values.length);
        String emoji = switch (dim) {
            case "genre" -> "🏷️";
            case "bpm" -> "⏱";
            case "rating" -> "⭐";
            case "year" -> "📅";
            default -> "📎";
        };
        for (int i = 0; i < shown; i++) {
            LabelAndValue lv = values[i];
            info("   " + emoji + "  " + lv.label + "  " + lv.value);
        }
        if (values.length > shown) {
            info("   … and " + (values.length - shown) + " more " + dim + " buckets");
        }
    }

    public record FacetLine(String emoji, String label, long count) {}

    private static void info(String message) {
        LOG.log(Logger.Level.INFO, message);
        System.out.println(message);
    }

    private static String chips(Map<String, List<String>> fields) {
        StringJoiner joiner = new StringJoiner(" · ");
        fields.forEach((field, values) -> joiner.add(chip(field, values)));
        return joiner.toString();
    }

    private static String chip(String field, List<String> values) {
        String emoji = switch (field) {
            case "artist" -> "🎤";
            case "title" -> "💿";
            case "genre" -> "🏷️";
            case "key" -> "🎹";
            case "album" -> "💽";
            default -> "📎";
        };
        return emoji + " \"" + field + ":" + String.join(", ", values) + "\"";
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String formatBpm(double bpm) {
        if (bpm == (long) bpm) {
            return Long.toString((long) bpm);
        }
        return String.format(Locale.ROOT, "%.1f", bpm);
    }

    private static String stars(int rating) {
        if (rating <= 0) {
            return "☆";
        }
        return "⭐".repeat(Math.min(5, rating));
    }
}
