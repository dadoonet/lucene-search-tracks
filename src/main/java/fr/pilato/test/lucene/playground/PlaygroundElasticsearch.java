package fr.pilato.test.lucene.playground;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import fr.pilato.test.lucene.Track;
import fr.pilato.test.lucene.TrackSearch;
import fr.pilato.test.lucene.TrackSearchElasticsearchImpl;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static fr.pilato.test.lucene.playground.PlaygroundModels.ElasticsearchStatus;

final class PlaygroundElasticsearch implements AutoCloseable {

    private final List<Track> corpus;
    private ElasticsearchSettings settings;
    private ElasticsearchClient client;
    private TrackSearchElasticsearchImpl index;
    private boolean ready;
    private String error;
    private int docs;

    PlaygroundElasticsearch(List<Track> corpus, ElasticsearchSettings settings) {
        this.corpus = List.copyOf(corpus);
        this.settings = settings == null ? ElasticsearchSettings.defaults() : settings;
    }

    synchronized ElasticsearchStatus status() {
        return new ElasticsearchStatus(
                settings.url(),
                settings.apiKey() != null,
                ready,
                error,
                docs);
    }

    synchronized boolean ready() {
        return ready;
    }

    synchronized TrackSearch trackSearch() {
        return ready ? index : null;
    }

    synchronized ElasticsearchStatus connect(ElasticsearchSettings next) {
        closeQuietly();
        settings = next == null ? ElasticsearchSettings.defaults() : next;
        ready = false;
        docs = 0;
        error = null;
        try {
            client = openClient(settings);
            client.info();
            index = new TrackSearchElasticsearchImpl(client);
            long start = System.nanoTime();
            index.rebuild(corpus);
            long esMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            docs = corpus.size();
            ready = true;
            System.out.println(IndexTiming.indexed("Elasticsearch", docs, esMs) + " at " + settings.url());
            return status();
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            closeQuietly();
            return status();
        }
    }

    synchronized ElasticsearchStatus connect(String url, String apiKey) {
        return connect(settings.withConnection(url, apiKey));
    }

    synchronized ElasticsearchStatus disconnect() {
        closeQuietly();
        settings = ElasticsearchSettings.fromEnv();
        ready = false;
        docs = 0;
        error = null;
        return status();
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        ready = false;
        docs = 0;
        if (index != null) {
            try {
                index.close();
            } catch (Exception _) {
                // close
            }
            index = null;
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception _) {
                // close
            }
            client = null;
        }
    }

    private static ElasticsearchClient openClient(ElasticsearchSettings settings) {
        return ElasticsearchClient.of(b -> {
            b.host(settings.url());
            if (settings.apiKey() != null) {
                b.apiKey(settings.apiKey());
            } else if (settings.password() != null) {
                b.usernameAndPassword("elastic", settings.password());
            }
            return b;
        });
    }
}
