package fr.pilato.test.lucene;

import java.util.Map;

public record TrackHit(Track track, float score, Map<String, String> highlights) {
    public TrackHit(Track track, float score) {
        this(track, score, Map.of());
    }

    public TrackHit {
        highlights = highlights == null ? Map.of() : Map.copyOf(highlights);
    }
}
