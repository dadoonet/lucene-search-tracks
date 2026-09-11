package fr.pilato.test.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TrackSearchIndex implements AutoCloseable {

    private final Directory directory;
    private final IndexWriter writer;
    private final Directory suggestionDirectory;
    private final AnalyzingInfixSuggester suggester;
    private final Object writeLock = new Object();
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    public TrackSearchIndex() throws IOException {
        directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(TrackAnalyzers.searchAnalyzer());
        writer = new IndexWriter(directory, config);
        suggestionDirectory = new ByteBuffersDirectory();
        suggester = new AnalyzingInfixSuggester(
                suggestionDirectory, TrackAnalyzers.searchAnalyzer());
    }

    public void rebuild(List<Track> tracks) throws IOException {
        synchronized (writeLock) {
            writer.deleteAll();
            for (Track track : tracks) {
                writer.addDocument(indexedDocument(track));
            }
            writer.commit();
            this.tracks.clear();
            for (Track track : tracks) {
                this.tracks.put(track.id(), track);
            }
            rebuildSuggester();
        }
    }

    public IndexSearcher searcher() throws IOException {
        return new IndexSearcher(DirectoryReader.open(writer));
    }

    public int numDocs() {
        synchronized (writeLock) {
            return writer.getDocStats().numDocs;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            suggester.close();
            suggestionDirectory.close();
            writer.close();
            directory.close();
        }
    }

    private static Document indexedDocument(Track track) throws IOException {
        return TrackFacets.config().build(TrackDocumentMapper.toDocument(track));
    }

    void rebuildSuggester() throws IOException {
        suggester.build(new TrackSuggestionInputIterator(tracks.values()));
    }

    private static List<SuggestionValue> suggestionsFor(Track track) {
        List<SuggestionValue> values = new ArrayList<>(3);
        addIfPresent(values, "title", track.title());
        addIfPresent(values, "artist", track.artist());
        addIfPresent(values, "genre", track.genre());
        return values;
    }

    private static void addIfPresent(List<SuggestionValue> values, String field, String text) {
        if (text != null && !text.isBlank()) {
            values.add(new SuggestionValue(text, field));
        }
    }

    private static String suggestionKey(SuggestionValue suggestion) {
        return suggestion.field() + "\0" + suggestion.text();
    }

    private record SuggestionValue(String text, String field) {}

    private static final class TrackSuggestionInputIterator implements InputIterator {
        private final Iterator<SuggestionValue> values;
        private SuggestionValue current;

        private TrackSuggestionInputIterator(Iterable<Track> tracks) {
            Map<String, SuggestionValue> distinct = new LinkedHashMap<>();
            for (Track track : tracks) {
                for (SuggestionValue suggestion : suggestionsFor(track)) {
                    distinct.putIfAbsent(suggestionKey(suggestion), suggestion);
                }
            }
            values = distinct.values().iterator();
        }

        @Override
        public BytesRef next() {
            if (!values.hasNext()) {
                current = null;
                return null;
            }
            current = values.next();
            return new BytesRef(current.text());
        }

        @Override
        public long weight() {
            return 1;
        }

        @Override
        public BytesRef payload() {
            return new BytesRef(current.field());
        }

        @Override
        public boolean hasPayloads() {
            return true;
        }

        @Override
        public Set<BytesRef> contexts() {
            return Set.of();
        }

        @Override
        public boolean hasContexts() {
            return false;
        }
    }
}
