package fr.pilato.test.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TrackSearchIndex implements AutoCloseable {

    private static final int SUGGEST_LIMIT = 10;

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

    public List<TrackSuggestion> suggest(String prefix) throws IOException {
        return suggest(prefix, null);
    }

    public List<TrackSuggestion> suggest(String prefix, Collection<Track> scope) throws IOException {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        if (scope != null && scope.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = null;
        if (scope != null) {
            allowed = new HashSet<>();
            for (Track track : scope) {
                for (TrackSuggestion suggestion : suggestionsFor(track)) {
                    allowed.add(suggestionKey(suggestion));
                }
            }
            if (allowed.isEmpty()) {
                return List.of();
            }
        }
        synchronized (writeLock) {
            int lookupCount = SUGGEST_LIMIT;
            if (allowed != null) {
                lookupCount = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(SUGGEST_LIMIT, suggester.getCount()));
            }
            List<Lookup.LookupResult> matches =
                    suggester.lookup(prefix, Set.of(), lookupCount, true, true);
            List<TrackSuggestion> result = new ArrayList<>(SUGGEST_LIMIT);
            for (Lookup.LookupResult match : matches) {
                String field = match.payload != null ? match.payload.utf8ToString() : "";
                String text = match.key.toString();
                if (allowed != null && !allowed.contains(suggestionKey(field, text))) {
                    continue;
                }
                String highlight = match.highlightKey != null ? match.highlightKey.toString() : text;
                result.add(new TrackSuggestion(text, field, highlight));
                if (result.size() >= SUGGEST_LIMIT) {
                    break;
                }
            }
            return List.copyOf(result);
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

    public long ramBytesUsed() throws IOException {
        synchronized (writeLock) {
            return sizeOf(directory) + sizeOf(suggestionDirectory);
        }
    }

    private static long sizeOf(Directory directory) throws IOException {
        long bytes = 0L;
        for (String name : directory.listAll()) {
            bytes += directory.fileLength(name);
        }
        return bytes;
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

    private static List<TrackSuggestion> suggestionsFor(Track track) {
        List<TrackSuggestion> values = new ArrayList<>(3);
        addIfPresent(values, "title", track.title());
        addIfPresent(values, "artist", track.artist());
        addIfPresent(values, "genre", track.genre());
        return values;
    }

    private static void addIfPresent(List<TrackSuggestion> values, String field, String text) {
        if (text != null && !text.isBlank()) {
            values.add(new TrackSuggestion(text, field));
        }
    }

    private static String suggestionKey(TrackSuggestion suggestion) {
        return suggestionKey(suggestion.field(), suggestion.text());
    }

    private static String suggestionKey(String field, String text) {
        return field + "\0" + text;
    }

    private static final class TrackSuggestionInputIterator implements InputIterator {
        private final Iterator<TrackSuggestion> values;
        private TrackSuggestion current;

        private TrackSuggestionInputIterator(Iterable<Track> tracks) {
            Map<String, TrackSuggestion> distinct = new LinkedHashMap<>();
            for (Track track : tracks) {
                for (TrackSuggestion suggestion : suggestionsFor(track)) {
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
