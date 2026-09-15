package fr.pilato.test.lucene.playground;

import fr.pilato.test.lucene.lucene.helpers.TrackAnalyzers;
import fr.pilato.test.lucene.lucene.helpers.TrackAnalyzers.AnalyzedToken;
import fr.pilato.test.lucene.lucene.helpers.TrackAnalyzers.Stage;

import java.util.ArrayList;
import java.util.List;

import static fr.pilato.test.lucene.playground.PlaygroundModels.AnalyzeCell;
import static fr.pilato.test.lucene.playground.PlaygroundModels.AnalyzeRow;

final class PlaygroundAnalyze {

    private PlaygroundAnalyze() {}

    static List<AnalyzeRow> rows(String text, List<Stage> stages) {
        if (text == null || text.isBlank() || stages.size() < 3) {
            return List.of();
        }
        List<AnalyzedToken> tokenizer = stages.get(0).tokens();
        List<AnalyzedToken> lower = stages.get(1).tokens();
        List<AnalyzedToken> folded = stages.get(2).tokens();
        List<AnalyzeRow> rows = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < tokenizer.size(); i++) {
            AnalyzedToken token = tokenizer.get(i);
            addDropped(rows, text, pos, token.start());
            String original = text.substring(token.start(), token.end());
            String tokenizerTerm = token.term();
            String lowerTerm = i < lower.size() ? lower.get(i).term() : "";
            String foldedTerm = i < folded.size() ? folded.get(i).term() : "";
            rows.add(new AnalyzeRow(List.of(
                    cell(original, "kept"),
                    cell(tokenizerTerm, tokenizerTerm.equals(original) ? "kept" : "changed"),
                    cell(lowerTerm, lowerTerm.equals(tokenizerTerm) ? "kept" : "changed"),
                    cell(foldedTerm, foldedTerm.equals(lowerTerm) ? "kept" : "changed"))));
            pos = token.end();
        }
        addDropped(rows, text, pos, text.length());
        return List.copyOf(rows);
    }

    private static void addDropped(List<AnalyzeRow> rows, String text, int from, int to) {
        if (from >= to) {
            return;
        }
        String dropped = text.substring(from, to).replaceAll("\\s+", "");
        if (dropped.isEmpty()) {
            return;
        }
        rows.add(new AnalyzeRow(List.of(
                cell(dropped, "kept"),
                cell(dropped, "deleted"),
                cell("", "empty"),
                cell("", "empty"))));
    }

    private static AnalyzeCell cell(String text, String state) {
        return new AnalyzeCell(text, state);
    }
}
