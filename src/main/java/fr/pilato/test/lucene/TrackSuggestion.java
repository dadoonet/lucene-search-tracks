package fr.pilato.test.lucene;

/** A track metadata value returned by the autocomplete suggester. */
public record TrackSuggestion(String text, String field, String highlight) {
    public TrackSuggestion(String text, String field) {
        this(text, field, text == null ? "" : text);
    }

    public TrackSuggestion {
        text = text == null ? "" : text;
        field = field == null ? "" : field;
        highlight = (highlight == null || highlight.isBlank()) ? text : highlight;
    }
}
