package fr.pilato.test.lucene;

import java.util.Map;

public record TrackFacetsResult(
        Map<String, Long> genres,
        long bpm120to130,
        Map<String, Long> ratings,
        long year2020s
) {}
