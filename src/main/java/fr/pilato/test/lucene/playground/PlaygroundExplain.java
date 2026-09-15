package fr.pilato.test.lucene.playground;

import org.apache.lucene.search.Explanation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.pilato.test.lucene.playground.PlaygroundModels.ExplainNode;

final class PlaygroundExplain {

    private static final Pattern CLAUSE = Pattern.compile(
            "(genre\\.raw\\.normalized|title\\.raw\\.normalized|artist\\.raw\\.normalized|"
                    + "key\\.code|title|artist|genre|album|label|comment):([^\\s)\\^]+)(\\*)?");

    private PlaygroundExplain() {}

    static ExplainNode from(Explanation explanation, List<String> tokens) {
        Node parsed = parseTree(explanation, tokens == null ? List.of() : tokens);
        return toView(fillDown(collapseUp(parsed), List.of()));
    }

    private static Node parseTree(Explanation explanation, List<String> tokens) {
        List<Node> details = new ArrayList<>();
        for (Explanation detail : explanation.getDetails()) {
            details.add(parseTree(detail, tokens));
        }
        return new Node(
                explanation.getValue().doubleValue(),
                explanation.getDescription(),
                parseKeys(explanation.getDescription(), tokens),
                List.copyOf(details));
    }

    static List<String> parseKeys(String description, List<String> tokens) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher matcher = CLAUSE.matcher(description);
        while (matcher.find()) {
            String field = matcher.group(1);
            String term = matcher.group(2);
            boolean prefix = matcher.group(3) != null;
            if ("genre.raw.normalized".equals(field)) {
                keys.add("filter:genre");
                continue;
            }
            if ("key.code".equals(field)) {
                keys.add("mustnot:key");
                continue;
            }
            String token = matchToken(term, tokens);
            if (token == null) {
                continue;
            }
            if (prefix || !token.equals(term)) {
                keys.add("prefix:" + field + ":" + token);
            } else {
                keys.add("term:" + field + ":" + token);
            }
        }
        return List.copyOf(keys);
    }

    private static String matchToken(String term, List<String> tokens) {
        if (tokens.contains(term)) {
            return term;
        }
        String best = null;
        for (String token : tokens) {
            if (term.startsWith(token) && (best == null || token.length() > best.length())) {
                best = token;
            }
        }
        return best;
    }

    private static Node collapseUp(Node node) {
        List<Node> details = node.details().stream().map(PlaygroundExplain::collapseUp).toList();
        Node updated = new Node(node.value(), node.description(), node.keys(), details);
        if (!updated.keys().isEmpty()) {
            return updated;
        }
        return new Node(updated.value(), updated.description(), collapse(details), details);
    }

    private static List<String> collapse(List<Node> details) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Node detail : details) {
            keys.addAll(detail.keys());
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        boolean query = false;
        boolean filter = false;
        for (String key : keys) {
            String token = tokenOf(key);
            if (token != null) {
                query = true;
                tokens.add(token);
            } else if (key.startsWith("filter:") || key.startsWith("mustnot:") || "bool".equals(key)) {
                filter = true;
            }
        }
        if ((query && filter) || tokens.size() > 1 || keys.contains("bool")) {
            return List.of("bool");
        }
        if (tokens.size() == 1) {
            keys.add("token:" + tokens.getFirst());
        }
        return List.copyOf(keys);
    }

    private static String tokenOf(String key) {
        if (key.startsWith("token:")) {
            return key.substring("token:".length());
        }
        if (key.startsWith("term:") || key.startsWith("prefix:")) {
            int last = key.lastIndexOf(':');
            return last < 0 ? null : key.substring(last + 1);
        }
        return null;
    }

    private static Node fillDown(Node node, List<String> parentKeys) {
        List<String> keys = node.keys().isEmpty() ? parentKeys : node.keys();
        List<Node> details = node.details().stream()
                .map(detail -> fillDown(detail, keys))
                .toList();
        return new Node(node.value(), node.description(), keys, details);
    }

    private static ExplainNode toView(Node node) {
        return new ExplainNode(
                node.value(),
                node.description(),
                node.keys(),
                node.details().stream().map(PlaygroundExplain::toView).toList());
    }

    private record Node(double value, String description, List<String> keys, List<Node> details) {}
}
