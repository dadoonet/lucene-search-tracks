package fr.pilato.test.lucene.playground;

public final class ElasticsearchNotReadyException extends RuntimeException {

    public ElasticsearchNotReadyException(String message) {
        super(message);
    }
}
