package fr.pilato.test.lucene.playground;

import org.apache.lucene.search.Explanation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static fr.pilato.test.lucene.playground.PlaygroundModels.ExplainNode;
import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundExplainTest {

    @Test
    void linksCompleteBobSinclarHouseClubTree() {
        ExplainNode tree = PlaygroundExplain.from(completeTree(), List.of("bob", "sinclar", "house"));

        assertThat(tree.keys()).containsExactly("bool");

        ExplainNode bob = tree.details().get(0);
        assertThat(bob.description()).isEqualTo("sum of:");
        assertThat(bob.keys()).contains("token:bob", "term:artist:bob");

        ExplainNode artistBob = bob.details().getFirst();
        assertThat(artistBob.description()).startsWith("weight(artist:bob ");
        assertThat(artistBob.keys()).containsExactly("term:artist:bob");
        assertThat(flatten(artistBob))
                .allSatisfy(node -> assertThat(node.keys()).contains("term:artist:bob"));
        assertThat(keyOf(artistBob, "boost")).containsExactly("term:artist:bob");

        ExplainNode sinclar = tree.details().get(1);
        assertThat(sinclar.keys()).contains("token:sinclar", "term:artist:sinclar");

        ExplainNode house = tree.details().get(2);
        assertThat(house.keys()).contains("token:house", "term:album:house", "prefix:album:house");
        assertThat(house.details().get(1).description()).isEqualTo("album:house*^0.375");
        assertThat(house.details().get(1).keys()).containsExactly("prefix:album:house");

        ExplainNode filter = tree.details().get(3);
        assertThat(filter.description()).isEqualTo("match on required clause, product of:");
        assertThat(filter.keys()).containsExactly("filter:genre");
        assertThat(filter.details().getFirst().description()).isEqualTo("# clause");
        assertThat(filter.details().getFirst().keys()).containsExactly("filter:genre");
        assertThat(filter.details().get(1).keys()).containsExactly("filter:genre");
    }

    private static List<String> keyOf(ExplainNode root, String description) {
        return flatten(root).stream()
                .filter(node -> node.description().equals(description))
                .findFirst()
                .orElseThrow()
                .keys();
    }

    private static List<ExplainNode> flatten(ExplainNode node) {
        List<ExplainNode> out = new ArrayList<>();
        walk(node, out);
        return out;
    }

    private static void walk(ExplainNode node, List<ExplainNode> out) {
        out.add(node);
        node.details().forEach(child -> walk(child, out));
    }

    private static Explanation completeTree() {
        return Explanation.match(
                15.770976,
                "sum of:",
                Explanation.match(
                        6.4865875,
                        "sum of:",
                        Explanation.match(
                                6.4865875,
                                "weight(artist:bob in 455) [BM25Similarity], result of:",
                                Explanation.match(
                                        6.4865875,
                                        "score(freq=1.0), computed as boost * idf * tf from:",
                                        Explanation.match(3.0, "boost"),
                                        Explanation.match(
                                                4.4697323,
                                                "idf, computed as log(1 + (N - n + 0.5) / (n + 0.5)) from:",
                                                Explanation.match(49, "n, number of documents containing term"),
                                                Explanation.match(4322, "N, total number of documents with field")),
                                        Explanation.match(
                                                0.4837417,
                                                "tf, computed as freq / (freq + k1 * (1 - b + b * dl / avgdl)) from:",
                                                Explanation.match(1.0, "freq, occurrences of term within document"),
                                                Explanation.match(1.2, "k1, term saturation parameter"),
                                                Explanation.match(0.75, "b, length normalization parameter"),
                                                Explanation.match(2.0, "dl, length of field"),
                                                Explanation.match(2.346136, "avgdl, average length of field"))))),
                Explanation.match(
                        6.707854,
                        "sum of:",
                        Explanation.match(
                                6.707854,
                                "weight(artist:sinclar in 455) [BM25Similarity], result of:")),
                Explanation.match(
                        2.576535,
                        "sum of:",
                        Explanation.match(
                                2.201535,
                                "weight(album:house in 455) [BM25Similarity], result of:"),
                        Explanation.match(0.375, "album:house*^0.375")),
                Explanation.match(
                        0.0,
                        "match on required clause, product of:",
                        Explanation.match(0.0, "# clause"),
                        Explanation.match(1.0, "genre.raw.normalized:club")));
    }
}
