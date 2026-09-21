package fr.pilato.test.lucene.playground;

import fr.pilato.test.lucene.TrackSearchLuceneImpl;
import fr.pilato.test.lucene.playground.helpers.PlaygroundLuceneHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static fr.pilato.test.lucene.playground.helpers.TrackFacets.CAMELOT_CODES;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetBucket;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class PlaygroundServiceTest {

    private static PlaygroundService service;

    @BeforeAll
    static void boot() throws Exception {
        service = PlaygroundService.boot();
    }

    @AfterAll
    static void close() throws Exception {
        service.close();
    }

    @Test
    void boot_usesTrackSearchLucene2_andKeepsExtrasOnTrackSearchLucene() throws Exception {
        assertThat(PlaygroundService.class.getDeclaredField("index").getType())
                .isEqualTo(TrackSearchLuceneImpl.class);
        assertThat(PlaygroundLuceneHelper.class).hasDeclaredMethods("searcher", "numDocs", "ramBytesUsed");
        assertThat(TrackSearchLuceneImpl.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("searcher", "numDocs", "ramBytesUsed");
    }

    @Test
    void analyze_showsTokenizerThenLowercaseThenFolding() {
        var result = service.analyze("Ultra Naté");
        assertThat(result.tokens()).containsExactly("ultra", "nate");
        assertThat(result.stages()).extracting(PlaygroundModels.AnalyzeStage::component)
                .containsExactly("StandardTokenizer", "LowerCaseFilter", "ASCIIFoldingFilter");
        assertThat(result.stages().get(0).tokens()).containsExactly("Ultra", "Naté");
        assertThat(result.stages().get(1).tokens()).containsExactly("ultra", "naté");
        assertThat(result.stages().get(2).tokens()).containsExactly("ultra", "nate");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().getFirst().cells())
                .extracting(PlaygroundModels.AnalyzeCell::text, PlaygroundModels.AnalyzeCell::state)
                .containsExactly(
                        tuple("Ultra", "kept"),
                        tuple("Ultra", "kept"),
                        tuple("ultra", "changed"),
                        tuple("ultra", "kept"));
        assertThat(result.rows().get(1).cells())
                .extracting(PlaygroundModels.AnalyzeCell::text, PlaygroundModels.AnalyzeCell::state)
                .containsExactly(
                        tuple("Naté", "kept"),
                        tuple("Naté", "kept"),
                        tuple("naté", "changed"),
                        tuple("nate", "changed"));
        assertThat(result.spans())
                .extracting(PlaygroundModels.TokenSpan::term, PlaygroundModels.TokenSpan::start)
                .containsExactly(tuple("Ultra", 0), tuple("Naté", 6));
    }

    @Test
    void analyze_longTitleKeepsStopWordsAndFoldsAccents() {
        var result = service.analyze(
                "Café del Mar — Around The World (François Kevorkian Mix)");
        assertThat(result.stages().getFirst().tokens())
                .contains("Café", "Around", "The", "World", "François", "Kevorkian", "Mix")
                .doesNotContain("—", "(", ")");
        assertThat(result.tokens())
                .contains("cafe", "around", "the", "world", "francois", "kevorkian", "mix")
                .doesNotContain("Café", "François");
        assertThat(result.rows())
                .anySatisfy(row -> {
                    assertThat(row.cells().get(0).text()).isEqualTo("—");
                    assertThat(row.cells().get(1).state()).isEqualTo("deleted");
                    assertThat(row.cells().get(2).state()).isEqualTo("empty");
                })
                .anySatisfy(row -> {
                    assertThat(row.cells().get(0).text()).isEqualTo("(");
                    assertThat(row.cells().get(1).state()).isEqualTo("deleted");
                })
                .anySatisfy(row -> {
                    assertThat(row.cells().get(0).text()).isEqualTo("Café");
                    assertThat(row.cells().get(3).text()).isEqualTo("cafe");
                    assertThat(row.cells().get(3).state()).isEqualTo("changed");
                })
                .anySatisfy(row -> {
                    assertThat(row.cells().get(0).text()).isEqualTo("The");
                    assertThat(row.cells().get(1).text()).isEqualTo("The");
                    assertThat(row.cells().get(2).text()).isEqualTo("the");
                    assertThat(row.cells().get(2).state()).isEqualTo("changed");
                });
    }

    @Test
    void map_indexesReferenceTrack() {
        var mapped = service.map(PlaygroundService.REFERENCE_ID);
        assertThat(mapped.title()).isEqualTo("Free (Bob Sinclar Remix)");
        assertThat(mapped.artist()).isEqualTo("Ultra Naté");
        assertThat(mapped.genre()).isEqualTo("Club");
        assertThat(mapped.key()).isEqualTo("4B");
        assertThat(mapped.bpm()).isEqualTo(128.0);
        assertThat(mapped.rating()).isEqualTo(3);
        assertThat(PlaygroundService.class.getResource("/public/tracks/" + PlaygroundService.AROUND_THE_WORLD_ID + ".jpg"))
                .isNotNull();
        assertThat(PlaygroundService.class.getResource("/public/tracks/" + PlaygroundService.REFERENCE_ID + ".jpg"))
                .isNotNull();
        assertThat(PlaygroundService.class.getResource("/public/tracks/" + PlaygroundService.CETTE_ANNEE_LA_ID + ".jpg"))
                .isNotNull();
        assertThat(mapped.picks())
                .extracting(PlaygroundModels.TrackPick::id, PlaygroundModels.TrackPick::label)
                .containsExactly(
                        tuple(PlaygroundService.AROUND_THE_WORLD_ID, "Around The World"),
                        tuple(PlaygroundService.REFERENCE_ID, "Ultra Naté"),
                        tuple(PlaygroundService.CETTE_ANNEE_LA_ID, "Cette année-là"));
        assertThat(mapped.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("title");
                    assertThat(field.luceneType()).isEqualTo("TextField");
                    assertThat(field.tokenized()).isTrue();
                    assertThat(field.tokens()).containsExactly("bob", "free", "remix", "sinclar");
                })
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("genre.raw.normalized");
                    assertThat(field.value()).isEqualTo("club");
                    assertThat(field.role()).isEqualTo("exact FILTER");
                })
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("bpm");
                    assertThat(field.value()).isEqualTo("128.0");
                    assertThat(field.role())
                            .startsWith("numeric range / sort")
                            .contains("IEEE 754")
                            .contains("numericValue() = 0x" + Long.toHexString(Double.doubleToLongBits(128.0)).toUpperCase());
                })
                .noneMatch(field -> "SortedSetDocValuesFacetField".equals(field.luceneType()))
                .noneMatch(field -> "dummy".equals(field.name()) || "dummy".equals(field.value()));
    }

    @Test
    void map_sortsAnalyzedTokensAlphanumerically() {
        var mapped = service.map(PlaygroundService.CETTE_ANNEE_LA_ID);
        assertThat(mapped.fields())
                .filteredOn(field -> "title".equals(field.name()) && field.tokenized())
                .extracting(PlaygroundModels.MappedField::tokens)
                .containsExactly(List.of("annee", "cette", "la"));
        assertThat(mapped.fields())
                .filteredOn(field -> "bpm".equals(field.name()))
                .extracting(PlaygroundModels.MappedField::role)
                .first()
                .asString()
                .contains("numericValue() = 0x405BB9999999999A");
    }

    @Test
    void invertedIndex_listsBobTitlePostings() throws Exception {
        var index = service.invertedIndex("bob", "title");
        assertThat(index.field()).isEqualTo("title");
        assertThat(index.numDocs()).isEqualTo(index.corpusSize());
        assertThat(index.docFreq()).isGreaterThan(0);
        assertThat(index.postings())
                .extracting(PlaygroundModels.TermPosting::title)
                .anyMatch(title -> title.contains("Bob") || title.toLowerCase().contains("bob"));
    }

    @Test
    void invertedIndex_listsBobArtistPostings() throws Exception {
        var title = service.invertedIndex("bob", "title");
        var artist = service.invertedIndex("bob", "artist");
        assertThat(artist.field()).isEqualTo("artist");
        assertThat(artist.term()).isEqualTo("bob");
        assertThat(artist.docFreq()).isGreaterThan(title.docFreq());
        assertThat(artist.postings())
                .extracting(PlaygroundModels.TermPosting::artist)
                .isNotEmpty()
                .allMatch(name -> name.toLowerCase().contains("bob"));
        assertThat(artist.postings())
                .extracting(PlaygroundModels.TermPosting::title)
                .noneMatch(name -> name.contains("Free (Bob Sinclar Remix)"));
    }

    @Test
    void search_bobMatchesBlogCountsAndExplainsTopHit() throws Exception {
        var bob = service.search(new SearchRequest("Bob", Map.of(), Map.of(), null));
        assertThat(bob.total()).isEqualTo(62);
        assertThat(bob.query()).contains("title:bob");
        assertThat(bob.hits()).isNotEmpty();
        assertThat(bob.hits().getFirst().explain()).contains("bob");
        assertThat(bob.tookMs()).isGreaterThanOrEqualTo(0);

        var club = service.search(new SearchRequest(
                "Bob", Map.of("genre", List.of("Club")), Map.of(), null));
        assertThat(club.total()).isEqualTo(26);
        assertThat(club.query()).contains("#");

        var minusKeys = service.search(new SearchRequest(
                "Bob",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")),
                null));
        assertThat(minusKeys.total()).isEqualTo(23);
        assertThat(minusKeys.query()).contains("-");
    }

    @Test
    void search_completeQueryExplainTreeLinksClauses() throws Exception {
        var result = service.search(new SearchRequest(
                "bob sinclar house",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")),
                null));
        var tree = result.hits().getFirst().explainTree();
        assertThat(tree).isNotNull();
        assertThat(tree.keys()).containsExactly("bool");
        assertThat(flattenKeys(tree))
                .contains("bool", "filter:genre", "token:bob", "token:sinclar", "token:house");
    }

    private static List<String> flattenKeys(PlaygroundModels.ExplainNode node) {
        List<String> keys = new java.util.ArrayList<>(node.keys());
        node.details().forEach(child -> keys.addAll(flattenKeys(child)));
        return keys;
    }

    @Test
    void search_bobHighlightsMatchingTitle() throws Exception {
        var bob = service.search(new SearchRequest("Bob", Map.of(), Map.of(), null));
        assertThat(bob.hits())
                .anySatisfy(hit -> {
                    assertThat(hit.title()).isEqualTo("Free (Bob Sinclar Remix)");
                    assertThat(hit.highlights().get("title")).contains("Free (<b>Bob</b> Sinclar Remix)");
                });
    }

    @Test
    void suggest_clubReturnsGenreAndTitle() throws Exception {
        assertThat(service.suggest("club").hits())
                .extracting(PlaygroundModels.SuggestHitView::text, PlaygroundModels.SuggestHitView::field)
                .contains(tuple("Club House", "genre"), tuple("In Da Club", "title"));
    }

    @Test
    void facets_bobCountsClubAndKeepsDanceWhenDrilling() throws Exception {
        var bob = service.facets("Bob", "");
        assertThat(count(bob, "genre", "Club")).isEqualTo(26);
        assertThat(count(bob, "bpm", "120 – 130")).isEqualTo(52);

        var drilled = service.facets("Bob", "Club");
        assertThat(drilled.drillSideways()).isTrue();
        assertThat(count(drilled, "genre", "Dance")).isGreaterThan(0);
        assertThat(count(drilled, "bpm", "120 – 130")).isLessThan(52);
    }

    @Test
    void facets_filtersMapGenre_isDrillSideways() throws Exception {
        var drilled = service.facets("Bob", Map.of("genre", List.of("Club")), Map.of());
        assertThat(drilled.drillSideways()).isTrue();
        assertThat(count(drilled, "genre", "Dance")).isGreaterThan(0);
        assertThat(count(drilled, "bpm", "120 – 130")).isLessThan(52);
    }

    @Test
    void facets_bpmFilter_narrowsOtherBpmBuckets() throws Exception {
        var filtered = service.facets("Bob", Map.of("bpm", List.of("120 – 130")), Map.of());
        assertThat(filtered.drillSideways()).isFalse();
        assertThat(count(filtered, "bpm", "120 – 130")).isEqualTo(52);
        assertThat(count(filtered, "bpm", "80 – 90")).isZero();
    }

    @Test
    void facets_mustNotGenre_hidesClubBucket() throws Exception {
        var excluded = service.facets("Bob", Map.of(), Map.of("genre", List.of("Club")));
        assertThat(count(excluded, "genre", "Club")).isZero();
        assertThat(count(excluded, "genre", "Dance")).isGreaterThan(0);
    }

    @Test
    void facets_ratingAlwaysShowsFiveToZeroDescending() throws Exception {
        var bob = service.facets("Bob", "");
        assertThat(labels(bob, "rating")).containsExactly("5", "4", "3", "2", "1", "0");
        assertThat(count(bob, "rating", "5")).isEqualTo(13);

        var fives = service.facets("Bob", Map.of("rating", List.of("5")), Map.of());
        assertThat(labels(fives, "rating")).containsExactly("5", "4", "3", "2", "1", "0");
        assertThat(count(fives, "rating", "5")).isEqualTo(13);
        assertThat(count(fives, "rating", "2")).isZero();
    }

    @Test
    void facets_yearSkipsMissingAndJunkYears() throws Exception {
        var all = service.facets("", "");
        assertThat(labels(all, "year"))
                .isNotEmpty()
                .doesNotContain("0–9")
                .first()
                .isEqualTo("1950–1959");
        assertThat(count(all, "year", "2020–2029")).isEqualTo(1395);

        var hits = service.search(new SearchRequest(
                "", Map.of("year", List.of("0–9")), Map.of(), null));
        assertThat(hits.total()).isZero();
    }

    @Test
    void facets_bpmAndYearSortedByKey() throws Exception {
        var bob = service.facets("Bob", "");
        List<String> bpm = labels(bob, "bpm");
        assertThat(bpm).isNotEmpty().isSortedAccordingTo(Comparator.comparingInt(PlaygroundServiceTest::bpmRank));
        List<String> years = labels(bob, "year");
        assertThat(years).isNotEmpty()
                .isSortedAccordingTo(Comparator.comparingInt(label -> Integer.parseInt(label.substring(0, 4))));
    }

    @Test
    void facets_keyShowsAllCamelotSlotsAndIgnoresKeyFilter() throws Exception {
        var bob = service.facets("Bob", "");
        assertThat(bob.dims()).extracting(PlaygroundModels.FacetDim::name)
                .containsExactly("genre", "rating", "year", "bpm", "key");
        assertThat(labels(bob, "key")).containsExactlyElementsOf(CAMELOT_CODES);
        long fourB = count(bob, "key", "4B");
        long fourA = count(bob, "key", "4A");
        assertThat(fourB).isGreaterThan(0);

        var filtered = service.facets("Bob", Map.of("key", List.of("4B")), Map.of());
        assertThat(count(filtered, "key", "4B")).isEqualTo(fourB);
        assertThat(count(filtered, "key", "4A")).isEqualTo(fourA);

        var nate = service.facets("Ultra Naté", "");
        assertThat(labels(nate, "key")).hasSize(24);
        assertThat(nate.dims().getLast().buckets().stream().anyMatch(bucket -> bucket.count() == 0))
                .isTrue();
    }

    @Test
    void facets_showsFacetsConfigRewrite() throws Exception {
        var rewrite = service.facets("Bob", "").rewrite();
        assertThat(rewrite.before())
                .extracting(
                        PlaygroundModels.FacetRewriteLine::luceneType,
                        PlaygroundModels.FacetRewriteLine::name,
                        PlaygroundModels.FacetRewriteLine::value)
                .containsExactly(tuple("SortedSetDocValuesFacetField", "genre", "Club"));
        assertThat(rewrite.after())
                .extracting(
                        PlaygroundModels.FacetRewriteLine::luceneType,
                        PlaygroundModels.FacetRewriteLine::name,
                        PlaygroundModels.FacetRewriteLine::value)
                .containsExactly(
                        tuple("SortedSetDocValuesField", "$facets", "genre\\u001FClub"),
                        tuple("StringField", "$facets", "genre\\u001FClub"),
                        tuple("StringField", "$facets", "genre"));
    }

    private static long count(PlaygroundModels.FacetsResponse response, String dim, String label) {
        return response.dims().stream()
                .filter(d -> dim.equals(d.name()))
                .flatMap(d -> d.buckets().stream())
                .filter(bucket -> label.equals(bucket.label()))
                .mapToLong(FacetBucket::count)
                .findFirst()
                .orElse(0L);
    }

    private static List<String> labels(PlaygroundModels.FacetsResponse response, String dim) {
        return response.dims().stream()
                .filter(d -> dim.equals(d.name()))
                .flatMap(d -> d.buckets().stream())
                .map(FacetBucket::label)
                .toList();
    }

    private static int bpmRank(String label) {
        if ("0 – 80".equals(label)) {
            return 0;
        }
        if ("220+".equals(label)) {
            return 220;
        }
        String from = label.split(" – ", 2)[0];
        return Integer.parseInt(from);
    }

    @Test
    void elasticsearch_isOffAfterBootWithoutCredentials() {
        var status = service.elasticsearch();
        assertThat(status.url()).isEqualTo("http://localhost:9200/");
        assertThat(status.apiKeySet()).isFalse();
        assertThat(status.ready()).isFalse();
        assertThat(status.docs()).isZero();
    }

    @Test
    void elasticsearch_unreachableHostStaysOfflineAndDoesNotThrow() {
        try {
            var status = service.connectElasticsearch(
                    "http://127.0.0.1:9/", "not-a-real-key");
            assertThat(status.ready()).isFalse();
            assertThat(status.url()).isEqualTo("http://127.0.0.1:9/");
            assertThat(status.apiKeySet()).isTrue();
            assertThat(status.error()).isNotBlank();
            assertThat(status.docs()).isZero();
        } finally {
            service.disconnectElasticsearch();
        }
    }

    @Test
    void search_elasticsearchBackend_fallsBackToLuceneWhenNotReady() throws Exception {
        var bob = service.search(new SearchRequest("Bob", Map.of(), Map.of(), null), "elasticsearch");
        assertThat(bob.total()).isEqualTo(62);
        assertThat(bob.query()).contains("title:bob");
        assertThat(bob.query()).doesNotContain("curl");
    }
}
