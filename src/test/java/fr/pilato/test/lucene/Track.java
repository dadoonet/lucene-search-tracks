package fr.pilato.test.lucene;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Track(
        String id,
        String title,
        String artist,
        String genre,
        String key,
        double bpm,
        int rating,
        int year,
        String album,
        String label,
        String comment
) {}
