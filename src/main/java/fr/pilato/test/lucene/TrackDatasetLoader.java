package fr.pilato.test.lucene.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.pilato.test.lucene.beans.Track;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TrackDatasetLoader {

    private static final String RESOURCE = "/tracks.ndjson";

    private TrackDatasetLoader() {}

    public static List<Track> load() {
        InputStream in = TrackDatasetLoader.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + RESOURCE);
        }
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<Track> tracks = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                tracks.add(mapper.readValue(line, Track.class));
            }
            return List.copyOf(tracks);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + RESOURCE, e);
        }
    }
}
