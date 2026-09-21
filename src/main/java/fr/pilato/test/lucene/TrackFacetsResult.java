package fr.pilato.test.lucene;

import java.util.Map;

public record TrackFacetsResult(
        Map<String, Long> genres,
        Map<String, Long> bpm,
        Map<String, Long> ratings,
        Map<String, Long> years,
        Map<String, Long> keys) {}
