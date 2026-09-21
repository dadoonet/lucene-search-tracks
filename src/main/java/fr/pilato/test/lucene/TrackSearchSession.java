package fr.pilato.test.lucene;

import java.util.List;

public interface TrackSearchSession {
    String printQuery();
    void execute() throws Exception;
    int totalHits();
    List<TrackHit> getHits();
    TrackFacetsResult getFacets();
    String printResponse();
}
