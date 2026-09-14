package fr.pilato.test.lucene;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackTestLogTest {

    @Test
    void search_printsQueryFiltersAndTopScoredHits() {
        Track holdOn = new Track(
                "1", "World Hold On (Children Of The Sky) (Club Mix 2)", "Bob Sinclar",
                "Club", "1A", 127.0, 5, 2006, "World Hold on CDS", null, "");
        Track free = new Track(
                "2", "Free (Bob Sinclar Remix)", "Ultra Naté",
                "Club", "4B", 128.0, 3, 0, null, null, "04B");

        String out = capture(() -> TrackTestLog.search(
                "World Hold On",
                Map.of("artist", List.of("Bob Sinclar")),
                Map.of(),
                List.of(new TrackHit(holdOn, 12.48f), new TrackHit(free, 3.10f))));

        assertThat(out).contains("🔎 searching for \"World Hold On\"");
        assertThat(out).contains("🎤 \"artist:Bob Sinclar\"");
        assertThat(out).contains("📊 2 hits");
        assertThat(out).contains("12.48");
        assertThat(out).contains("World Hold On");
        assertThat(out).contains("Bob Sinclar");
    }

    @Test
    void search_printsMustNotsAndTruncates() {
        Track sample = new Track(
                "1", "Digane", "Bob Sinclar", "Club", "9A", 124.0, 3, 2024, null, null, "");
        List<TrackHit> hits = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new TrackHit(
                        new Track(String.valueOf(i), sample.title() + " " + i, sample.artist(),
                                sample.genre(), sample.key(), sample.bpm(), sample.rating(),
                                sample.year(), sample.album(), sample.label(), sample.comment()),
                        5.0f - (i * 0.1f)))
                .toList();

        String out = capture(() -> TrackTestLog.search(
                "Bob",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")),
                hits));

        assertThat(out).contains("🔎 searching for \"Bob\"");
        assertThat(out).contains("🏷️ \"genre:Club\"");
        assertThat(out).contains("🚫 🎹 \"key:4A, 4B\"");
        assertThat(out).contains("📊 12 hits");
        assertThat(out).contains("showing top " + TrackTestLog.TOP_HITS);
        assertThat(out).contains("and 4 more");
    }

    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
