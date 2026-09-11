# Lucene search tracks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Maven test-only demo that indexes a Rekordbox track snapshot in Lucene RAM and pins Parts 1–5 of the Lucene Bean Search posts (mapping, rebuild, search, suggest, facets).

**Architecture:** Empty `src/main`. Helpers and tests live in `src/test/java/fr/pilato/test/lucene`. `tracks.ndjson` is a committed snapshot dumped once from Diggo’s `RekordboxLibrary`; tests never open SQLCipher. One `TrackSearchIndex` (`ByteBuffersDirectory` + suggester) is rebuilt from that list.

**Tech Stack:** Java 25, Maven (latest stable 3.9+), Lucene (`core`, `analysis-common`, `facet`, `suggest` — one shared latest stable version), JUnit Jupiter, AssertJ, Jackson. Every library and Maven plugin version is looked up on Maven Central at implementation time.

## Global Constraints

- Package `fr.pilato.test.lucene`; groupId `fr.pilato.test.lucene`; artifactId `lucene-search-tracks`.
- **JDK 25:** `maven.compiler.release` is `25`. Run tests with a JDK 25+ toolchain.
- **Latest stable versions, always.** Before writing or editing `pom.xml`, look up the current **stable** release on Maven Central (or the Apache plugin pages) for every dependency **and** every Maven plugin you declare. Use that version — do not copy numbers from this plan, from Diggo, or from the blog posts if a newer stable exists. Skip RC / milestone / beta / alpha. The four Lucene artefacts share **one** version. The POM snippet below is illustrative only (versions as of 2026-09-11).
- Lucene APIs follow the blog (ByteBuffersDirectory, AnalyzingInfixSuggester, FacetsConfig, DrillSideways). Do not copy Diggo extras (`*.present`, artwork, uuid, paths, upsert).
- Everything under `src/test`. No Diggo Maven dependency in the published POM.
- Dump tool is one-shot and **not** committed (SQLCipher + Rekordbox key must not ship).
- Pin post numbers: Bob=62, Bob+Club=27, Bob+Club−4A/4B=24; facets under Bob: Club=26, BPM 120–130=52, rating 5=13, year 2020s=15. If the dump disagrees, fail — do not loosen.
- Null strings index as `""`. Blank `q` + no filters → `MatchAllDocsQuery`.
- TDD: failing test first, watch it fail, then implement. Commit after each task.
- Path: `/Users/david/IdeaProjects/blog-tests/lucene-search-tracks`

## File structure

| File | Responsibility |
|------|----------------|
| `pom.xml` | Java 25, latest Lucene / JUnit / AssertJ / Jackson / compiler / Surefire |
| `.gitignore` | `target/`, `.idea/`, `*.iml` |
| `README.md` | How to run tests + pointer to the five posts |
| `src/test/resources/tracks.ndjson` | One JSON object per visible Rekordbox track |
| `Track.java` | Flat record matching one NDJSON line |
| `TrackDataset.java` | `load()` → `List<Track>` |
| `TrackIndexFields.java` | Field name constants |
| `TrackAnalyzers.java` | Search analyzer + `tokenize` |
| `TrackDocumentMapper.java` | Bean → Lucene `Document` |
| `TrackSearchIndex.java` | RAM index + suggester, `rebuild` only |
| `TrackLuceneQueryBuilder.java` | Structured `BooleanQuery` + hit join |
| `TrackSuggestion.java` | Suggest hit (`text`, `field`, `highlight`) |
| `TrackFacets.java` | Shared `FacetsConfig` + BPM ranges |
| `Track*Test.java` | One test class per blog part |

---

### Task 1: Maven skeleton + Rekordbox NDJSON + dataset loader

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `src/test/java/fr/pilato/test/lucene/Track.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackDataset.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackDatasetTest.java`
- Create: `src/test/resources/tracks.ndjson` (via one-shot dump, not a dump class in git)

**Interfaces:**
- Consumes: Diggo `RekordboxLibrary` on the author’s machine (dump only)
- Produces: `Track` record; `TrackDataset.load()` → `List<Track>`

- [ ] **Step 1: Look up latest stable versions, then write `pom.xml` and `.gitignore`**

On Maven Central, resolve the newest **stable** of:

| Coordinate | Notes |
|------------|-------|
| `org.apache.lucene:lucene-core` | Same version for `lucene-analysis-common`, `lucene-facet`, `lucene-suggest` |
| `org.junit.jupiter:junit-jupiter` | JUnit 6.x BOM or aggregator |
| `org.assertj:assertj-core` | |
| `com.fasterxml.jackson.core:jackson-databind` | |
| `org.apache.maven.plugins:maven-compiler-plugin` | Must support `--release 25` |
| `org.apache.maven.plugins:maven-surefire-plugin` | JUnit Platform 6 |

Do **not** paste the illustrative versions below if Central has moved on.

`pom.xml` (replace every `VERSION` with what you just looked up; `maven.compiler.release` stays `25`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>fr.pilato.test.lucene</groupId>
    <artifactId>lucene-search-tracks</artifactId>
    <version>1.0-SNAPSHOT</version>
    <name>lucene-search-tracks</name>
    <description>In-process Lucene demo over a Rekordbox track snapshot (blog series Parts 1–5).</description>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <lucene.version>LOOK_UP</lucene.version>
        <junit.version>LOOK_UP</junit.version>
        <assertj.version>LOOK_UP</assertj.version>
        <jackson.version>LOOK_UP</jackson.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.apache.lucene</groupId>
            <artifactId>lucene-core</artifactId>
            <version>${lucene.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.lucene</groupId>
            <artifactId>lucene-analysis-common</artifactId>
            <version>${lucene.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.lucene</groupId>
            <artifactId>lucene-facet</artifactId>
            <version>${lucene.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.lucene</groupId>
            <artifactId>lucene-suggest</artifactId>
            <version>${lucene.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>LOOK_UP</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>LOOK_UP</version>
            </plugin>
        </plugins>
    </build>
</project>
```

`.gitignore`:

```
target/
.idea/
*.iml
.DS_Store
```

- [ ] **Step 2: Write the failing dataset test**

`src/test/java/fr/pilato/test/lucene/TrackDatasetTest.java`:

```java
package fr.pilato.test.lucene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackDatasetTest {

    @Test
    void load_readsRekordboxSnapshot() {
        List<Track> tracks = TrackDataset.load();
        assertThat(tracks).hasSizeGreaterThan(4000);
        assertThat(tracks)
                .anySatisfy(t -> {
                    assertThat(t.id()).isEqualTo("255465792");
                    assertThat(t.title()).isEqualTo("Free (Bob Sinclar Remix)");
                    assertThat(t.artist()).isEqualTo("Ultra Naté");
                    assertThat(t.genre()).isEqualTo("Club");
                    assertThat(t.key()).isEqualTo("4B");
                    assertThat(t.bpm()).isEqualTo(128.0);
                    assertThat(t.rating()).isEqualTo(3);
                });
    }
}
```

- [ ] **Step 3: Run the test — it must fail to compile (no `Track` / `TrackDataset`)**

Run: `mvn -q test -Dtest=TrackDatasetTest`

Expected: COMPILE FAIL (`TrackDataset` cannot be found)

- [ ] **Step 4: Dump `tracks.ndjson` from Diggo (do not commit the dump source)**

Write `/tmp/DumpTracks.java` (delete after the dump):

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.pilato.diggo.lib.RekordboxLibrary;
import fr.pilato.diggo.lib.model.Track;

import java.nio.file.Files;
import java.nio.file.Path;

public class DumpTracks {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        ObjectMapper om = new ObjectMapper();
        try (RekordboxLibrary lib = RekordboxLibrary.open();
             var writer = Files.newBufferedWriter(out)) {
            for (Track t : lib.tracks()) {
                ObjectNode node = om.createObjectNode();
                node.put("id", t.id());
                node.put("title", t.title());
                put(node, "artist", t.artist() == null ? null : t.artist().name());
                put(node, "genre", t.genre() == null ? null : t.genre().name());
                put(node, "key", t.key() == null ? null : t.key().name());
                node.put("bpm", t.bpm());
                node.put("rating", t.ratingStars());
                node.put("year", t.year());
                put(node, "album", t.album() == null ? null : t.album().name());
                put(node, "label", t.label() == null ? null : t.label().name());
                put(node, "comment", t.comment());
                writer.write(om.writeValueAsString(node));
                writer.write('\n');
            }
        }
        System.out.println("Wrote " + out);
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
```

From Diggo:

```bash
cd /Users/david/IdeaProjects/perso/diggo
mvn -q -pl diggo-lib -am test-compile
CP=$(mvn -q -pl diggo-lib -am dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=/dev/stdout):diggo-lib/target/classes
mkdir -p /Users/david/IdeaProjects/blog-tests/lucene-search-tracks/src/test/resources
javac --release 25 -cp "$CP" -d /tmp /tmp/DumpTracks.java
java -cp "/tmp:$CP" DumpTracks \
  /Users/david/IdeaProjects/blog-tests/lucene-search-tracks/src/test/resources/tracks.ndjson
wc -l /Users/david/IdeaProjects/blog-tests/lucene-search-tracks/src/test/resources/tracks.ndjson
rm -f /tmp/DumpTracks.java /tmp/DumpTracks.class
```

Expected: ~4300 lines. Confirm the Ultra Naté line exists (`rg "255465792" tracks.ndjson`).

- [ ] **Step 5: Implement `Track` and `TrackDataset`**

`Track.java`:

```java
package fr.pilato.test.lucene;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Track(
        String id,
        String title,
        String artist,
        String genre,
        String key,
        double bpm,
        int rating,
        int year,
        String album,
        String label,
        String comment
) {}
```

`TrackDataset.java`:

```java
package fr.pilato.test.lucene;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TrackDataset {

    private static final String RESOURCE = "/tracks.ndjson";

    private TrackDataset() {}

    public static List<Track> load() {
        InputStream in = TrackDataset.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + RESOURCE);
        }
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<Track> tracks = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                tracks.add(mapper.readValue(line, Track.class));
            }
            return List.copyOf(tracks);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + RESOURCE, e);
        }
    }
}
```

- [ ] **Step 6: Run the test — it must pass**

Run: `mvn -q test -Dtest=TrackDatasetTest`

Expected: PASS, size > 4000, Ultra Naté present

- [ ] **Step 7: Commit**

```bash
git add pom.xml .gitignore \
  src/test/java/fr/pilato/test/lucene/Track.java \
  src/test/java/fr/pilato/test/lucene/TrackDataset.java \
  src/test/java/fr/pilato/test/lucene/TrackDatasetTest.java \
  src/test/resources/tracks.ndjson
git commit -m "$(cat <<'EOF'
⚙️ Add Rekordbox NDJSON snapshot and Track dataset loader

Tests need a SQL-free corpus that matches the Lucene blog posts.
EOF
)"
```

---

### Task 2: Mapping (Part 1)

**Files:**
- Create: `src/test/java/fr/pilato/test/lucene/TrackIndexFields.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackAnalyzers.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackDocumentMapper.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackDocumentMapperTest.java`

**Interfaces:**
- Consumes: `Track`
- Produces: `TrackIndexFields` constants; `TrackAnalyzers.searchAnalyzer()` / `tokenize(String)`; `TrackDocumentMapper.toDocument(Track)` → `Document`

- [ ] **Step 1: Write the failing mapper test**

```java
package fr.pilato.test.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackDocumentMapperTest {

    @Test
    void toDocument_indexesIdentityTextKeywordsAndNumerics() throws Exception {
        Track track = new Track(
                "255465792",
                "Free (Bob Sinclar Remix)",
                "Ultra Naté",
                "Club",
                "4B",
                128.0,
                3,
                1997,
                "Album",
                "Label",
                "comment");

        Document doc = TrackDocumentMapper.toDocument(track);

        assertThat(doc.get(TrackIndexFields.ID)).isEqualTo("255465792");
        assertThat(tokens(TrackIndexFields.TITLE, doc.get(TrackIndexFields.TITLE)))
                .contains("free", "bob", "sinclar", "remix");
        assertThat(tokens(TrackIndexFields.ARTIST, "Ultra Naté")).contains("ultra", "nate");
        assertThat(doc.get(TrackIndexFields.GENRE_RAW)).isEqualTo("Club");
        assertThat(doc.get(TrackIndexFields.GENRE_RAW_NORMALIZED)).isEqualTo("club");
        assertThat(doc.get(TrackIndexFields.KEY_CODE)).isEqualTo("4b");
        assertThat(doc.getField(TrackIndexFields.BPM).numericValue().doubleValue()).isEqualTo(128.0);
        assertThat(doc.getField(TrackIndexFields.RATING).numericValue().intValue()).isEqualTo(3);
        assertThat(names(doc, TrackIndexFields.TITLE_RAW)).isNotEmpty();
        assertThat(names(doc, TrackIndexFields.ARTIST_RAW)).isNotEmpty();
    }

    private static List<String> names(Document doc, String field) {
        List<String> names = new ArrayList<>();
        for (IndexableField f : doc.getFields()) {
            if (field.equals(f.name())) {
                names.add(f.stringValue());
            }
        }
        return names;
    }

    private static List<String> tokens(String field, String text) throws Exception {
        List<String> tokens = new ArrayList<>();
        try (Analyzer analyzer = TrackAnalyzers.searchAnalyzer();
             TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(term.toString());
            }
            stream.end();
        }
        return tokens;
    }
}
```

- [ ] **Step 2: Run test — compile failure**

Run: `mvn -q test -Dtest=TrackDocumentMapperTest`

Expected: COMPILE FAIL (`TrackDocumentMapper` / `TrackIndexFields` missing)

- [ ] **Step 3: Implement fields, analyzer, mapper**

`TrackIndexFields.java`:

```java
package fr.pilato.test.lucene;

public final class TrackIndexFields {
    public static final String ID = "id";
    public static final String TITLE = "title";
    public static final String ARTIST = "artist";
    public static final String GENRE = "genre";
    public static final String ALBUM = "album";
    public static final String LABEL = "label";
    public static final String COMMENT = "comment";
    public static final String TITLE_RAW = "title.raw";
    public static final String TITLE_RAW_NORMALIZED = "title.raw.normalized";
    public static final String ARTIST_RAW = "artist.raw";
    public static final String ARTIST_RAW_NORMALIZED = "artist.raw.normalized";
    public static final String GENRE_RAW = "genre.raw";
    public static final String GENRE_RAW_NORMALIZED = "genre.raw.normalized";
    public static final String KEY_CODE = "key.code";
    public static final String BPM = "bpm";
    public static final String RATING = "rating";
    public static final String YEAR = "year";

    private TrackIndexFields() {}
}
```

`TrackAnalyzers.java` — copy the Diggo analyzer (StandardTokenizer + LowerCaseFilter + ASCIIFoldingFilter) and `tokenize(String)` using `searchAnalyzer().tokenStream(TITLE, text)`. `PREFIX_MIN = 1`.

`TrackDocumentMapper.java`:

```java
package fr.pilato.test.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;

import java.text.Normalizer;
import java.util.Locale;

public final class TrackDocumentMapper {

    private TrackDocumentMapper() {}

    public static Document toDocument(Track t) {
        Document doc = new Document();
        doc.add(new StringField(TrackIndexFields.ID, t.id(), Field.Store.YES));
        addText(doc, TrackIndexFields.TITLE, t.title());
        addText(doc, TrackIndexFields.ARTIST, t.artist());
        addText(doc, TrackIndexFields.GENRE, t.genre());
        addText(doc, TrackIndexFields.ALBUM, t.album());
        addText(doc, TrackIndexFields.LABEL, t.label());
        addText(doc, TrackIndexFields.COMMENT, t.comment());
        addKeyword(doc, TrackIndexFields.TITLE_RAW, TrackIndexFields.TITLE_RAW_NORMALIZED, t.title());
        addKeyword(doc, TrackIndexFields.ARTIST_RAW, TrackIndexFields.ARTIST_RAW_NORMALIZED, t.artist());
        addKeyword(doc, TrackIndexFields.GENRE_RAW, TrackIndexFields.GENRE_RAW_NORMALIZED, t.genre());
        doc.add(new StringField(TrackIndexFields.KEY_CODE, normalize(t.key()), Field.Store.NO));
        doc.add(new DoubleField(TrackIndexFields.BPM, t.bpm(), Field.Store.YES));
        doc.add(new IntField(TrackIndexFields.RATING, t.rating(), Field.Store.YES));
        doc.add(new IntField(TrackIndexFields.YEAR, t.year(), Field.Store.YES));
        String genre = nfc(t.genre());
        if (!genre.isEmpty()) {
            doc.add(new SortedSetDocValuesFacetField("genre", genre));
        }
        return doc;
    }

    static String nfc(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    static String normalize(String s) {
        return nfc(s).toLowerCase(Locale.ROOT);
    }

    private static void addText(Document doc, String field, String value) {
        doc.add(new TextField(field, nfc(value), Field.Store.YES));
    }

    private static void addKeyword(Document doc, String raw, String normalized, String value) {
        String nfc = nfc(value);
        doc.add(new StringField(raw, nfc, Field.Store.YES));
        doc.add(new StringField(normalized, normalize(nfc), Field.Store.NO));
    }
}
```

Note: `Stored` title TextField is used by the mapper test (`doc.get(TITLE)`). Keep `Store.YES` on text fields as in the post.

- [ ] **Step 4: Run test — pass**

Run: `mvn -q test -Dtest=TrackDocumentMapperTest`

Expected: PASS (`nate` from `Naté`, `club`, `4b`)

- [ ] **Step 5: Commit**

```bash
git add src/test/java/fr/pilato/test/lucene/TrackIndexFields.java \
  src/test/java/fr/pilato/test/lucene/TrackAnalyzers.java \
  src/test/java/fr/pilato/test/lucene/TrackDocumentMapper.java \
  src/test/java/fr/pilato/test/lucene/TrackDocumentMapperTest.java
git commit -m "$(cat <<'EOF'
⚙️ Map Track beans to Lucene documents

Index-time mapping is the contract the later search and facet tests rely on.
EOF
)"
```

---

### Task 3: In-memory index rebuild (Part 2)

**Files:**
- Create: `src/test/java/fr/pilato/test/lucene/TrackFacets.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackSearchIndex.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackSearchIndexTest.java`

**Interfaces:**
- Consumes: `TrackDocumentMapper.toDocument`, `TrackAnalyzers.searchAnalyzer()`
- Produces: `TrackFacets.config()` → `FacetsConfig`; `TrackSearchIndex` with `rebuild(List<Track>)`, `searcher()`, `numDocs()`, `close()`

- [ ] **Step 1: Write the failing index test**

```java
package fr.pilato.test.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackSearchIndexTest {

    @Test
    void rebuild_indexesEveryTrackInRam() throws Exception {
        List<Track> tracks = TrackDataset.load();
        try (TrackSearchIndex index = new TrackSearchIndex()) {
            index.rebuild(tracks);
            assertThat(index.numDocs()).isEqualTo(tracks.size());
            IndexSearcher searcher = index.searcher();
            try (IndexReader reader = searcher.getIndexReader()) {
                assertThat(reader.numDocs()).isEqualTo(tracks.size());
            }
        }
    }
}
```

- [ ] **Step 2: Run test — compile failure**

Run: `mvn -q test -Dtest=TrackSearchIndexTest`

Expected: COMPILE FAIL (`TrackSearchIndex` missing)

- [ ] **Step 3: Implement `TrackFacets` + `TrackSearchIndex` (rebuild only, no upsert)**

`TrackFacets.java`:

```java
package fr.pilato.test.lucene;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.range.DoubleRange;

public final class TrackFacets {
    public static final String GENRE = "genre";
    private static final FacetsConfig CONFIG = new FacetsConfig();

    private TrackFacets() {}

    public static FacetsConfig config() {
        return CONFIG;
    }

    /** 10-BPM buckets. `120 – 130` is [120, 130). */
    public static DoubleRange[] bpmRanges() {
        DoubleRange[] ranges = new DoubleRange[16];
        ranges[0] = new DoubleRange("0 – 80", 0.0, true, 80.0, false);
        for (int i = 0; i < 14; i++) {
            double from = 80.0 + (i * 10.0);
            double to = from + 10.0;
            ranges[i + 1] = new DoubleRange(
                    ((int) from) + " – " + ((int) to), from, true, to, false);
        }
        ranges[15] = new DoubleRange("220+", 220.0, true, Double.POSITIVE_INFINITY, false);
        return ranges;
    }
}
```

`TrackSearchIndex`: constructor opens `ByteBuffersDirectory` + `IndexWriter(TrackAnalyzers.searchAnalyzer())` and a second directory + `AnalyzingInfixSuggester` (same analyzer). `rebuild`: lock, `deleteAll`, `addDocument(TrackFacets.config().build(TrackDocumentMapper.toDocument(t)))` for each track, `commit`, keep `LinkedHashMap<String,Track>` for suggest (Task 5 can fill `rebuildSuggester`; for this task call an empty/private rebuild that builds from title/artist/genre — implement the iterator now so Task 5 does not reshape the index).

Implement suggester rebuild in this task (cheap, avoids a second rewrite): `InputIterator` of distinct title/artist/genre, `payload` = field name, `weight` = 1. `suggest(String)` can wait for Task 5; a package-private `rebuildSuggester()` is enough.

`searcher()` = `new IndexSearcher(DirectoryReader.open(writer))`.  
`numDocs()` = `writer.getDocStats().numDocs`.  
`close()`: suggester, suggestionDirectory, writer, directory.

- [ ] **Step 4: Run test — pass**

Run: `mvn -q test -Dtest=TrackSearchIndexTest`

Expected: PASS, `numDocs` equals dataset size (~4300)

- [ ] **Step 5: Commit**

```bash
git add src/test/java/fr/pilato/test/lucene/TrackFacets.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchIndex.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchIndexTest.java
git commit -m "$(cat <<'EOF'
⚙️ Rebuild the Lucene track index in RAM

A full replace at startup is enough for the demo; no upsert yet.
EOF
)"
```

---

### Task 4: Search (Part 3)

**Files:**
- Create: `src/test/java/fr/pilato/test/lucene/TrackLuceneQueryBuilder.java`
- Create: `src/test/java/fr/pilato/test/lucene/TrackSearchTest.java`

**Interfaces:**
- Consumes: `TrackSearchIndex.searcher()`, `TrackAnalyzers.tokenize`, field constants
- Produces: `TrackLuceneQueryBuilder.buildStructured(String q, Map<String,List<String>> filters, Map<String,List<String>> mustNots)` → `Query`; `search(IndexSearcher, Query, List<Track>)` → `List<Track>`

Boosts: title 4, artist 3, genre 2, album 1.5, label 1, comment 0.5, prefix × 0.25.

Free text: tokenize `q`. Each token is a MUST of a SHOULD-group across those six fields (`minShouldMatch = 1`). Only the **last** token also gets a `PrefixQuery` at 0.25 × field boost. One token ⇒ that SHOULD-group alone (matches the post’s `Query.toString()`).

Filters: `FILTER` `TermQuery` on `genre.raw.normalized` / `artist.raw.normalized` / `title.raw.normalized` / `key.code` after `TrackDocumentMapper.normalize`. Several values on one key = SHOULD, `minShouldMatch = 1`.

Must-not: same leaves under `MUST_NOT`. If the builder would be only `MUST_NOT`, add `MUST` `MatchAllDocsQuery`. Blank everything → `MatchAllDocsQuery`.

`search`: `TopDocs` with `max(1, reader.numDocs())`, read stored `id`, join to corpus map, preserve score order.

- [ ] **Step 1: Write the failing search test**

```java
package fr.pilato.test.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackSearchTest {

    private static List<Track> corpus;
    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        corpus = TrackDataset.load();
        index = new TrackSearchIndex();
        index.rebuild(corpus);
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void bob_returns62Hits() throws Exception {
        assertThat(search("Bob", Map.of(), Map.of())).hasSize(62);
    }

    @Test
    void bobClub_returns27Hits() throws Exception {
        List<Track> hits = search("Bob", Map.of("genre", List.of("Club")), Map.of());
        assertThat(hits).hasSize(27);
        assertThat(titles(hits)).contains("Free (Bob Sinclar Remix)");
        assertThat(titles(hits)).doesNotContain("TRIANGLE DES BERMUDES", "Give Me Love");
    }

    @Test
    void bobClubMinusKeys_returns24Hits() throws Exception {
        List<Track> hits = search(
                "Bob",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")));
        assertThat(hits).hasSize(24);
        assertThat(titles(hits)).doesNotContain(
                "Crazy (Bob Sinclar vs. Dimitri Vegas & Like Mike remix)",
                "Free (Bob Sinclar Remix)");
        assertThat(titles(hits)).contains("I Feel For You");
    }

    @Test
    void lastTokenIsPrefix_butInfixIsNot() throws Exception {
        assertThat(titles(search("bob sincla", Map.of(), Map.of())))
                .anyMatch(t -> t.toLowerCase().contains("sinclar"));
        assertThat(search("bo sinclar", Map.of(), Map.of())).isEmpty();
        assertThat(titles(search("ouse", Map.of(), Map.of())))
                .noneMatch(t -> t.equalsIgnoreCase("House") || t.toLowerCase().contains("house"));
    }

    @Test
    void queryString_marksFilterAndMustNot() {
        Query q = TrackLuceneQueryBuilder.buildStructured(
                "Bob",
                Map.of("genre", List.of("Club")),
                Map.of("key", List.of("4A", "4B")));
        String printed = q.toString();
        assertThat(printed).contains("#").contains("-");
    }

    private static List<Track> search(
            String q, Map<String, List<String>> filters, Map<String, List<String>> mustNots)
            throws Exception {
        Query lucene = TrackLuceneQueryBuilder.buildStructured(q, filters, mustNots);
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            return TrackLuceneQueryBuilder.search(searcher, lucene, corpus);
        }
    }

    private static List<String> titles(List<Track> tracks) {
        return tracks.stream().map(Track::title).toList();
    }
}
```

If exact titles differ in casing/punctuation, adjust the strings to the dump (`rg` in `tracks.ndjson`) **without changing the counts**.

- [ ] **Step 2: Run test — compile failure then assertion failure**

Run: `mvn -q test -Dtest=TrackSearchTest`

Expected: COMPILE FAIL, then after a stub `buildStructured` returning `MatchAllDocsQuery`, FAIL on `hasSize(62)` (would be ~4300)

- [ ] **Step 3: Implement `TrackLuceneQueryBuilder`**

Follow Diggo’s `freeTextFromTokens` / `addFreeTextField` / `fieldFilter` **without** fuzzy, `*:present`, bpm ranges, or artwork. `key` → `TermQuery` on `key.code` with `normalize`. `genre`/`artist`/`title` → corresponding `*.raw.normalized`.

`search(IndexSearcher, Query, List<Track>)`: build `Map<String,Track>` by id; `searcher.search(query, Math.max(1, searcher.getIndexReader().numDocs()))`; stored `id`.

- [ ] **Step 4: Run test — pass**

Run: `mvn -q test -Dtest=TrackSearchTest`

Expected: PASS with 62 / 27 / 24. If sizes differ, stop and report dump vs post — do not change assertions.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/fr/pilato/test/lucene/TrackLuceneQueryBuilder.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchTest.java
git commit -m "$(cat <<'EOF'
⚙️ Pin blog search queries on the Rekordbox snapshot

MUST / FILTER / MUST_NOT must reproduce the Part 3 hit counts.
EOF
)"
```

---

### Task 5: Suggest (Part 4)

**Files:**
- Create: `src/test/java/fr/pilato/test/lucene/TrackSuggestion.java`
- Modify: `src/test/java/fr/pilato/test/lucene/TrackSearchIndex.java` (add `suggest`)
- Create: `src/test/java/fr/pilato/test/lucene/TrackSuggestTest.java`

**Interfaces:**
- Consumes: suggester rebuilt in Task 3
- Produces: `record TrackSuggestion(String text, String field, String highlight)`; `TrackSearchIndex.suggest(String prefix)` and `suggest(String prefix, Collection<Track> scope)`

Lookup: `suggester.lookup(prefix, Set.of(), limit, false, true)` — `allTermsRequired=false`, `highlight=true` as in the post. Cap at 10 after optional scope filter. Empty prefix → empty list. Empty scope → empty list. `scope == null` → whole dictionary. When scoped, request up to `suggester.getCount()` hits then cap.

- [ ] **Step 1: Write the failing suggest test**

```java
package fr.pilato.test.lucene;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TrackSuggestTest {

    private static List<Track> corpus;
    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        corpus = TrackDataset.load();
        index = new TrackSearchIndex();
        index.rebuild(corpus);
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void club_returnsGenreAndTitle() throws Exception {
        List<TrackSuggestion> hits = index.suggest("club");
        assertThat(hits)
                .extracting(TrackSuggestion::text, TrackSuggestion::field)
                .contains(tuple("Club House", "genre"))
                .contains(tuple("In Da Club", "title"));
        assertThat(hits)
                .filteredOn(h -> "Club House".equals(h.text()))
                .first()
                .extracting(TrackSuggestion::highlight)
                .asString()
                .containsIgnoringCase("club");
    }

    @Test
    void madonna_returnsArtist() throws Exception {
        assertThat(index.suggest("Madonna"))
                .extracting(TrackSuggestion::text, TrackSuggestion::field)
                .contains(tuple("Madonna", "artist"));
    }

    @Test
    void applyingGenreChip_isFilterNotFreeText() throws Exception {
        List<Track> withQ = TrackLuceneQueryBuilder.search(
                index.searcher(),
                TrackLuceneQueryBuilder.buildStructured(
                        "club", Map.of("genre", List.of("Club House")), Map.of()),
                corpus);
        List<Track> chipOnly = TrackLuceneQueryBuilder.search(
                index.searcher(),
                TrackLuceneQueryBuilder.buildStructured(
                        "", Map.of("genre", List.of("Club House")), Map.of()),
                corpus);
        assertThat(chipOnly.size()).isGreaterThanOrEqualTo(withQ.size());
    }

    @Test
    void emptyScope_returnsNothing() throws Exception {
        assertThat(index.suggest("club", List.of())).isEmpty();
    }
}
```

Close readers in `applyingGenreChip` (try-with-resources on `searcher.getIndexReader()`).

- [ ] **Step 2: Run test — fail (no `suggest`)**

Run: `mvn -q test -Dtest=TrackSuggestTest`

Expected: COMPILE FAIL

- [ ] **Step 3: Add `TrackSuggestion` and `suggest` methods**

Mirror Diggo’s `TrackSearchIndex.suggest` / `TrackSuggestionInputIterator` using `Track.artist()` as a `String`. Dedup key = `field + "\0" + text`.

- [ ] **Step 4: Run test — pass**

Run: `mvn -q test -Dtest=TrackSuggestTest`

Expected: PASS. If *Club House* / *In Da Club* / *Madonna* are missing from this dump, fail and report — do not swap in other titles.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/fr/pilato/test/lucene/TrackSuggestion.java \
  src/test/java/fr/pilato/test/lucene/TrackSearchIndex.java \
  src/test/java/fr/pilato/test/lucene/TrackSuggestTest.java
git commit -m "$(cat <<'EOF'
⚙️ Add infix autocomplete beside the track index

Suggestions become FILTER chips; they must not stay in q.
EOF
)"
```

---

### Task 6: Facets (Part 5) + README

**Files:**
- Modify: `src/test/java/fr/pilato/test/lucene/TrackFacets.java` if helper methods help
- Create: `src/test/java/fr/pilato/test/lucene/TrackFacetsTest.java`
- Create: `README.md`

**Interfaces:**
- Consumes: Part 3 query + `TrackFacets.config()` + `bpmRanges()`
- Produces: tests only (no `FacetService`). Counting lives in the test / a small `TrackFacetCounts` helper if the test would otherwise exceed ~80 lines.

Under `q=Bob`, collector `n=1`:

| Bucket | Count |
|--------|-------|
| genre Club | 26 |
| BPM range labeled `120 – 130` | 52 |
| rating 5 | 13 |
| years 2020–2029 summed | 15 |

`DrillSideways`: base = free text Bob **without** genre FILTER; `drillDown.add("genre", TermQuery(GENRE_RAW, "Club"))`. BPM histogram must be smaller than unfiltered-Bob BPM `120 – 130`. Genre children still include Dance.

If Club search hits are 27 and the Club facet is 26, keep both assertions and add a one-line comment pointing at the posts. If they match, still pin 26 as specified; only change the spec/posts after a human decision.

- [ ] **Step 1: Write the failing facets test**

```java
package fr.pilato.test.lucene;

import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.LongValueFacetCounts;
import org.apache.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackFacetsTest {

    private static TrackSearchIndex index;

    @BeforeAll
    static void rebuild() throws Exception {
        index = new TrackSearchIndex();
        index.rebuild(TrackDataset.load());
    }

    @AfterAll
    static void close() throws Exception {
        index.close();
    }

    @Test
    void bob_countsGenreBpmRatingYear() throws Exception {
        Query lucene = TrackLuceneQueryBuilder.buildStructured("Bob", Map.of(), Map.of());
        IndexSearcher searcher = index.searcher();
        try (IndexReader reader = searcher.getIndexReader()) {
            var state = new DefaultSortedSetDocValuesReaderState(reader, TrackFacets.config());
            FacetsCollector fc = FacetsCollectorManager.search(
                            searcher, lucene, 1, new FacetsCollectorManager())
                    .facetsCollector();
            Facets genres = new SortedSetDocValuesFacetCounts(state, fc);
            Facets bpm = new DoubleRangeFacetCounts(
                    TrackIndexFields.BPM, fc, TrackFacets.bpmRanges());
            Facets rating = new LongValueFacetCounts(TrackIndexFields.RATING, fc);
            Facets year = new LongValueFacetCounts(TrackIndexFields.YEAR, fc);

            assertThat(count(genres.getAllChildren("genre"), "Club")).isEqualTo(26);
            assertThat(count(bpm.getAllChildren(TrackIndexFields.BPM), "120 – 130")).isEqualTo(52);
            assertThat(rating.getSpecificValue(TrackIndexFields.RATING, "5").intValue()).isEqualTo(13);
            long twenties = 0;
            for (LabelAndValue lv : year.getAllChildren(TrackIndexFields.YEAR).labelValues) {
                int y = Integer.parseInt(lv.label);
                if (y >= 2020 && y <= 2029) {
                    twenties += lv.value.longValue();
                }
            }
            assertThat(twenties).isEqualTo(15);
        }
    }
}
```

Add a second test `drillSideways_keepsOtherGenres` using `DrillDownQuery` + `DrillSideways`. Override `buildFacetsResult` to wrap `SortedSetDocValuesFacetCounts` + `DoubleRangeFacetCounts` in `MultiFacets` (see the post). Assert genre children contain Dance; BPM `120 – 130` count is `< 52`.

Helper `count(FacetResult, label)` walks `labelValues`.

Check Lucene `LongValueFacetCounts.getSpecificValue` / `getAllChildren` signatures at implementation time if the snippet does not compile; use `getAllChildren("rating")` and find label `"5"` instead.

- [ ] **Step 2: Run test — fail on counts or missing API**

Run: `mvn -q test -Dtest=TrackFacetsTest`

Expected: FAIL (missing helper and/or wrong counts until counting code is correct)

- [ ] **Step 3: Make the facet test pass** (counting is the implementation; no extra production class required unless the test is unreadable)

- [ ] **Step 4: Write `README.md`**

```markdown
# lucene-search-tracks

In-process [Apache Lucene](https://lucene.apache.org/) demo used by the
**Lucene Bean Search** series (Parts 1–5). Tests only: a Rekordbox track
snapshot is indexed in RAM (`ByteBuffersDirectory`), then searched,
autocompleted, and faceted.

```bash
mvn test
```

Requires **Java 25**. The NDJSON under `src/test/resources/tracks.ndjson` is a
snapshot of a local Rekordbox library; tests never open SQLCipher.

| Post | Test class |
|------|------------|
| Part 1 Mapping | `TrackDocumentMapperTest` |
| Part 2 Index | `TrackSearchIndexTest` |
| Part 3 Search | `TrackSearchTest` |
| Part 4 Suggest | `TrackSuggestTest` |
| Part 5 Facets | `TrackFacetsTest` |
```

Align the README table with the markdown-tables rule (padded columns).

- [ ] **Step 5: Run the full suite**

Run: `mvn test`

Expected: BUILD SUCCESS, all five parts green

- [ ] **Step 6: Commit**

```bash
git add src/test/java/fr/pilato/test/lucene/TrackFacetsTest.java \
  src/test/java/fr/pilato/test/lucene/TrackFacets.java \
  README.md
git commit -m "$(cat <<'EOF'
⚙️ Pin faceted navigation counts from Part 5

Genre / BPM / rating / year histograms must match the blog screenshots.
EOF
)"
```

---

## Self-review (plan vs spec)

| Spec section | Task |
|--------------|------|
| Maven / package / Java 25 / latest deps + plugins | Task 1 |
| Empty `src/main`, tests-only helpers | All tasks |
| NDJSON dump, no Diggo in POM | Task 1 |
| `Track` flat record + `TrackDataset.load` | Task 1 |
| Analyzer + full field table including `*.raw` and SSDV genre | Task 2 |
| `ByteBuffersDirectory` rebuild, no upsert | Task 3 |
| Structured MUST/FILTER/MUST_NOT + 62/27/24 | Task 4 |
| Infix suggest + FILTER chip + scope | Task 5 |
| Facet counts 26/52/13/15 + DrillSideways | Task 6 |
| Article 7 Elasticsearch | Explicitly out of scope |

No upsert, no UI, no SQLCipher in git. Pinned numbers are not marked optional.
