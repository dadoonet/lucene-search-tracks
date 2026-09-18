package fr.pilato.test.lucene.playground;

final class IndexTiming {

    private IndexTiming() {}

    static String indexed(String engine, int docs, long ms) {
        return engine + " indexed " + docs + " tracks in " + ms + " ms";
    }
}
