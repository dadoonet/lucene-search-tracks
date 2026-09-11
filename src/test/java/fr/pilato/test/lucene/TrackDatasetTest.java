package fr.pilato.test.lucene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackDatasetTest {

    @Test
    void load_readsRekordboxSnapshot() {
        List<Track> tracks = TrackDataset.load();
        assertThat(tracks).hasSizeGreaterThan(4000);
        assertThat(tracks)
                .anySatisfy(t -> {
                    assertThat(t.id()).isEqualTo("255465792");
                    assertThat(t.title()).isEqualTo("Free (Bob Sinclar Remix)");
                    assertThat(t.artist()).isEqualTo("Ultra Naté");
                    assertThat(t.genre()).isEqualTo("Club");
                    assertThat(t.key()).isEqualTo("4B");
                    assertThat(t.bpm()).isEqualTo(128.0);
                    assertThat(t.rating()).isEqualTo(3);
                });
    }
}
