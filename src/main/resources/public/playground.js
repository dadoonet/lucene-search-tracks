const JAVA_KEYWORDS = new Set([
  "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
  "class", "const", "continue", "default", "do", "double", "else", "enum",
  "extends", "final", "finally", "float", "for", "goto", "if", "implements",
  "import", "instanceof", "int", "interface", "long", "native", "new",
  "package", "private", "protected", "public", "return", "short", "static",
  "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
  "transient", "try", "void", "volatile", "while", "var", "record", "sealed",
  "permits", "yield", "true", "false", "null"
]);

const chapters = {
  analyze: {
    kicker: "Part 1 · before the Document",
    title: "Analyzer",
    lede: "Start from the sentence. Apply each Lucene stage from the pane on the right.",
    snippet: `Analyzer analyzer = new Analyzer() {
  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    Tokenizer source = new StandardTokenizer();
    TokenStream filter = new LowerCaseFilter(source);
    filter = new ASCIIFoldingFilter(filter);
    return new TokenStreamComponents(source, filter);
  }
};
// Analyze a text
TokenStream ts = analyzer.tokenStream("title", "Around The World");`,
    render(root) {
      analyzeStep = -1;
      root.innerHTML = `
        <div class="controls">
          <label class="field">Text
            <input id="text" type="text" value="Around The World" autocomplete="off">
          </label>
          <div class="presets">
            ${preset("Around The World")}
            ${preset("Ultra Naté")}
            ${preset("Café del Mar — Around The World (François Kevorkian Mix)", "Café / François mix")}
          </div>
        </div>`;
      bindText("text", () => runAnalyze());
      root.querySelectorAll("[data-preset]").forEach((button) => {
        button.onclick = () => {
          document.getElementById("text").value = button.dataset.preset;
          analyzeStep = -1;
          runAnalyze();
        };
      });
      runAnalyze();
    }
  },
  map: {
    kicker: "Part 1 · bean → Document",
    title: "Mapping",
    lede: "Pick a track. Lucene stores it as these fields — TextField for full text, StringField for an exact FILTER.",
    snippet: `doc.add(new TextField("title", title, Store.YES));
doc.add(new StringField("genre.raw.normalized", "club", Store.YES));
doc.add(new DoubleField("bpm", 128.0, Store.YES));`,
    render(root) {
      root.innerHTML = `
        <div class="controls">
          <label class="field">Track
            <select id="map-track"></select>
          </label>
          <article class="track-card" id="track-card" hidden>
            <img class="track-card-art" id="track-card-art" alt="">
            <div class="track-card-body">
              <h3 class="track-card-title" id="track-card-title"></h3>
              <dl class="track-card-meta" id="track-card-meta"></dl>
            </div>
          </article>
        </div>`;
      runMap();
    }
  },
  index: {
    kicker: "Part 2 · ByteBuffersDirectory",
    title: "Inverted index",
    lede: "The term is no longer in the title — the title is in the term. Type a token, read the posting list.",
    snippet: `// We will use an in-memory index
Directory dir = new ByteBuffersDirectory();

// Create the index writer with the analyzer
IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer));

// Create Lucene doc for track #255465792: Ultra Naté - Free (Bob Sinclar Remix)
Document doc255465792 = mapper.toDocument(Tracks.trackFrom(255465792));
writer.addDocument(doc255465792);

// Index track #172523747: Daft Punk - Around The World
Document doc172523747 = mapper.toDocument(Tracks.trackFrom(172523747));
writer.addDocument(doc172523747);

// Index track #106352474: Claude François - Cette année-là
Document doc106352474 = mapper.toDocument(Tracks.trackFrom(106352474));
writer.addDocument(doc106352474);

// Commit all the documents that have been indexed so far
writer.commit();`,
    render(root) {
      root.innerHTML = `
        <div class="controls">
          <label class="field">Term
            <input id="term" type="text" value="bob" autocomplete="off">
          </label>
          <label class="field">Field
            <select id="index-field">
              <option value="title" selected>title</option>
              <option value="artist">artist</option>
              <option value="genre">genre</option>
              <option value="album">album</option>
              <option value="label">label</option>
              <option value="comment">comment</option>
            </select>
          </label>
          <div class="presets">
            ${preset("bob")}${preset("nate")}${preset("house")}
          </div>
        </div>`;
      bindText("term", () => runIndex());
      document.getElementById("index-field").onchange = () => runIndex();
      root.querySelectorAll("[data-preset]").forEach((button) => {
        button.onclick = () => {
          document.getElementById("term").value = button.dataset.preset;
          runIndex();
        };
      });
      runIndex();
    }
  },
  search: {
    kicker: "Part 3 · BooleanQuery",
    title: "Search",
    lede: "Each analyzed token is a SHOULD across fields. The last token also gets a PrefixQuery. FILTER and MUST_NOT wrap that BooleanQuery.",
    snippet: `// Open an index searcher using the same writer
IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer));

TokenStream ts = analyzer.tokenStream("title", "Bob");
// → bob

// build query for bob
BooleanQuery.Builder bob = buildQuery("bob");

Query q = bob.build();

// Search and retrieve the 10 first hits
TopDocs hits = searcher.search(q, 10);

// Explain how the score is computed for the first hit
searcher.explain(q, hits.scoreDocs[0].doc);`,
    render(root) {
      root.innerHTML = `
        <div class="controls">
          <label class="field">q
            <input id="q" type="text" value="Bob" autocomplete="off">
          </label>
          <label class="field">FILTER genre
            <input id="genre" type="text" placeholder="Club" autocomplete="off">
          </label>
          <label class="field">MUST_NOT key
            <input id="minus" type="text" placeholder="4A, 4B" autocomplete="off">
          </label>
          <div class="presets">
            <button type="button" class="ghost" data-demo="bob">Bob → 62</button>
            <button type="button" class="ghost" data-demo="club">+ Club → 26</button>
            <button type="button" class="ghost" data-demo="keys">− 4A,4B → 23</button>
            <button type="button" class="ghost" data-demo="prefix">bob sincla</button>
            <button type="button" class="ghost" data-demo="ouse">ouse</button>
          </div>
        </div>`;
      const apply = (q, genre, minus) => {
        document.getElementById("q").value = q;
        document.getElementById("genre").value = genre;
        document.getElementById("minus").value = minus;
        runSearch();
      };
      root.querySelector("[data-demo=bob]").onclick = () => apply("Bob", "", "");
      root.querySelector("[data-demo=club]").onclick = () => apply("Bob", "Club", "");
      root.querySelector("[data-demo=keys]").onclick = () => apply("Bob", "Club", "4A, 4B");
      root.querySelector("[data-demo=prefix]").onclick = () => apply("bob sincla", "", "");
      root.querySelector("[data-demo=ouse]").onclick = () => apply("ouse", "", "");
      ["q", "genre", "minus"].forEach((id) => bindText(id, () => runSearch()));
      runSearch();
    }
  },
  facets: {
    kicker: "Part 5 · DrillSideways",
    title: "Facets",
    lede: "Counts follow the query. First enrich the Document for categorical dims, FacetsConfig.build at index time; numbers were already facet-ready.",
    snippet: `FacetsConfig facetsConfig = new FacetsConfig();

// We update the mapper as categorical dims need a facet field
doc.add(new SortedSetDocValuesFacetField("genre", "Club"));
// This produces behind the scene:
// doc.add(new SortedSetDocValuesField("$facets", new BytesRef("genre\\u001FClub")))
// doc.add(new StringField("$facets", "genre\\u001FClub", Store.NO))
// doc.add(new StringField("$facets", "genre", Store.NO))

// Index track #255465792 with the facet fields
Document doc255465792 = mapper.toDocument(Tracks.trackFrom(255465792));
writer.addDocument(facetsConfig.build(doc255465792));

FacetsCollector fc = FacetsCollectorManager.search(searcher, q, 1, manager)
    .facetsCollector();
Facets genres = new SortedSetDocValuesFacetCounts(state, fc);
Facets bpm = new DoubleRangeFacetCounts("bpm", fc, bpmRanges());
new DrillSideways(searcher, config, state).search(drillDown, 1);`,
    render(root) {
      root.innerHTML = `
        <div class="controls">
          <label class="field">q
            <input id="fq" type="text" value="Bob" autocomplete="off">
          </label>
          <label class="field">Drill-down genre
            <input id="drill" type="text" placeholder="Club" autocomplete="off">
          </label>
          <div class="presets">
            <button type="button" class="ghost" data-demo="bob">Bob</button>
            <button type="button" class="ghost" data-demo="club">Bob + Club</button>
          </div>
        </div>`;
      root.querySelector("[data-demo=bob]").onclick = () => {
        document.getElementById("fq").value = "Bob";
        document.getElementById("drill").value = "";
        runFacets();
      };
      root.querySelector("[data-demo=club]").onclick = () => {
        document.getElementById("fq").value = "Bob";
        document.getElementById("drill").value = "Club";
        runFacets();
      };
      ["fq", "drill"].forEach((id) => bindText(id, () => runFacets()));
      runFacets();
    }
  },
  suggest: {
    kicker: "Part 6 · AnalyzingInfixSuggester",
    title: "Suggest",
    lede: "A second RAM directory holds the suggester. Hover a row to see lookup() become that LookupResult — highlightKey, field, and the FILTER chip.",
    snippet: `Directory suggestionDirectory = new ByteBuffersDirectory();
AnalyzingInfixSuggester suggester = new AnalyzingInfixSuggester(
        suggestionDirectory, analyzer);
suggester.build(new TrackSuggestionInputIterator(tracks.values()));
List<Lookup.LookupResult> matches =
        suggester.lookup("club", Set.of(), 10, true, true);
Lookup.LookupResult match = matches.get(0);
String text = match.key.toString();
String field = match.payload.utf8ToString();
String highlight = match.highlightKey.toString();`,
    render(root) {
      root.innerHTML = `
        <div class="controls">
          <label class="field">Prefix
            <input id="prefix" type="text" value="club" autocomplete="off">
          </label>
          <div class="presets">
            ${preset("club")}${preset("Madonna")}${preset("sincla")}
          </div>
        </div>`;
      bindText("prefix", () => runSuggest());
      root.querySelectorAll("[data-preset]").forEach((button) => {
        button.onclick = () => {
          document.getElementById("prefix").value = button.dataset.preset;
          runSuggest();
        };
      });
      runSuggest();
    }
  },
  highlight: {
    kicker: "Part 7 · UnifiedHighlighter",
    title: "Highlighting",
    lede: "The query that scores a hit also marks the stored text. Bold tags follow token offsets — including the last-token PrefixQuery you already saw on suggest.",
    snippet: `UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
    .withMaxLength(10_000)
    .withBreakIterator(WholeBreakIterator::new)
    .build();
Map<String, String[]> hl = highlighter.highlightFields(
        new String[]{"title", "artist", "genre", "album", "label", "comment"},
        q, hits);`,
    render(root) {
      root.innerHTML = `
        <div class="controls">
          <label class="field">q
            <input id="hq" type="text" value="Bob" autocomplete="off">
          </label>
          <div class="presets">
            ${preset("Bob")}${preset("nate")}${preset("sincla")}${preset("ouse")}
          </div>
        </div>`;
      bindText("hq", () => runHighlight());
      root.querySelectorAll("[data-preset]").forEach((button) => {
        button.onclick = () => {
          document.getElementById("hq").value = button.dataset.preset;
          runHighlight();
        };
      });
      runHighlight();
    }
  },
  demo: {
    kicker: "All together",
    title: "Demo",
    lede: "Type to search. A genre or artist suggestion pins a chip; a title fills the bar. Click a Camelot slice to filter by key. Click a chip to flip filter in / filter out. Highlighted fields show where the query matched.",
    snippet: `Query q = TrackLuceneQueryBuilder.buildStructured(text, filters, mustNots);
TopDocs hits = searcher.search(q, 25);
UnifiedHighlighter.builder(searcher, analyzer).build()
        .highlightFields(new String[]{"title", "artist", "genre"}, q, hits);
new DrillSideways(searcher, config, state).search(drillDown, 1);`,
    render(root) {
      demoState = { chips: [], suggest: [], open: false, gen: 0 };
      const demoQ = new URLSearchParams(location.search).get("q") || "";
      root.innerHTML = `
        <div class="demo">
          <div class="demo-search">
            <div class="demo-q-head">
              <label class="demo-q-kicker" for="demo-q">Search</label>
              <span class="demo-engine" id="demo-backend" role="radiogroup" aria-label="Engine">
                <button type="button" class="demo-engine-opt" data-backend="lucene" aria-pressed="false">Lucene</button>
                <button type="button" class="demo-engine-opt" data-backend="elasticsearch" aria-pressed="false">Elasticsearch</button>
              </span>
            </div>
            <label class="demo-q-shell">
              <span class="demo-q-row">
                <i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i>
                <input id="demo-q" type="search" value="${escapeAttr(demoQ)}" autocomplete="off" spellcheck="false" placeholder="title, artist, genre…">
              </span>
            </label>
            <ul id="demo-suggest" class="demo-suggest" hidden></ul>
          </div>
          <div id="demo-chips" class="demo-chips" aria-live="polite"></div>
          <div class="demo-split">
            <aside id="demo-facets" class="demo-facets" aria-label="Facets"></aside>
            <div class="demo-results">
              <p class="demo-total" id="demo-total"></p>
              <div class="demo-table-wrap">
                <table class="demo-table">
                  <thead>
                    <tr><th>Title</th><th>Artist</th><th>Genre</th><th>Rating</th><th>Key</th></tr>
                  </thead>
                  <tbody id="demo-hits"></tbody>
                </table>
              </div>
            </div>
          </div>
        </div>`;
      bindDemo();
      document.getElementById("demo-backend").addEventListener("click", (event) => {
        const button = event.target.closest("[data-backend]");
        if (!button) {
          return;
        }
        if (button.dataset.backend === "elasticsearch" && !esStatus.ready) {
          document.getElementById("es-settings")?.click();
          return;
        }
        setDemoBackend(button.dataset.backend);
        runDemo();
      });
      setDemoBackend(demoEngine);
      runDemo();
    }
  }
};

function preset(value, label) {
  const text = label == null ? value : label;
  return `<button type="button" data-preset="${escapeAttr(value)}">${escapeHtml(text)}</button>`;
}

function bindText(id, fn) {
  const el = document.getElementById(id);
  let t;
  el.addEventListener("input", () => {
    clearTimeout(t);
    t = setTimeout(fn, 160);
  });
}

function escapeHtml(value) {
  return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
}

function escapeAttr(value) {
  return escapeHtml(value);
}

function highlightJava(source) {
  const rules = [
    { type: "cmt", re: /^\/\/[^\n]*/ },
    { type: "cmt", re: /^\/\*[\s\S]*?\*\// },
    { type: "str", re: /^"(?:\\.|[^"\\])*"/ },
    { type: "str", re: /^'(?:\\.|[^'\\])*'/ },
    { type: "num", re: /^\d[\d_]*(\.\d+)?[fFdDlL]?/ },
    { type: "word", re: /^[A-Za-z_$][A-Za-z0-9_$]*/ }
  ];
  let i = 0;
  let out = "";
  while (i < source.length) {
    const rest = source.slice(i);
    let hit = null;
    for (const rule of rules) {
      const match = rest.match(rule.re);
      if (match) {
        hit = { type: rule.type, text: match[0] };
        break;
      }
    }
    if (!hit) {
      out += escapeHtml(rest[0]);
      i += 1;
      continue;
    }
    let type = hit.type;
    if (type === "word") {
      if (JAVA_KEYWORDS.has(hit.text)) {
        type = "kw";
      } else if (/^[A-Z][A-Z0-9_]+$/.test(hit.text)) {
        type = "const";
      } else if (/^[A-Z]/.test(hit.text)) {
        type = "type";
      } else {
        type = "";
      }
    }
    const escaped = escapeHtml(hit.text);
    out += type ? `<span class="tok-${type}">${escaped}</span>` : escaped;
    i += hit.text.length;
  }
  return out;
}

function setCues(tokens) {
  document.getElementById("cues").innerHTML =
      (tokens || []).map((token) => `<span class="cue">${escapeHtml(token)}</span>`).join("");
}

function readout(html) {
  document.getElementById("readout").innerHTML = html;
}

async function getJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(jsonError(body) || response.statusText);
  }
  return response.json();
}

function jsonError(body) {
  if (!body) {
    return "";
  }
  try {
    const json = JSON.parse(body);
    return json.error || body;
  } catch {
    return body;
  }
}

const ANALYZE_STEPS = [
  { id: 0, label: "Tokenizer" },
  { id: 1, label: "Lowercase" },
  { id: 2, label: "Asciifolding" }
];

let analyzeStep = -1;
let analyzeData = null;

async function runAnalyze() {
  const text = document.getElementById("text").value;
  analyzeData = await getJson("/api/analyze", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text })
  });
  renderAnalyze();
}

function renderAnalyze() {
  const data = analyzeData;
  if (!data) {
    return;
  }
  const tokenizer = data.stages?.[0]?.tokens || [];
  const lower = data.stages?.[1]?.tokens || [];
  const folded = data.stages?.[2]?.tokens || [];
  const buttons = ANALYZE_STEPS.map((step) => `
    <button type="button" class="ghost${analyzeStep === step.id ? " is-on" : ""}" data-step="${step.id}">
      ${step.label}
    </button>`).join("");
  let body = `<p class="bbl-sentence">${escapeHtml(data.text)}</p>`;
  if (analyzeStep >= 0) {
    const columns = [tokenColumn(tokenizer)];
    if (analyzeStep >= 1) {
      columns.push(`<div class="bbl-arrow">→</div>`, tokenColumn(lower, tokenizer));
    }
    if (analyzeStep >= 2) {
      columns.push(`<div class="bbl-arrow">→</div>`, tokenColumn(folded, lower));
    }
    body += `
      <div class="bbl-down">↓</div>
      <div class="bbl-flow">${columns.join("")}</div>`;
  }
  const indexed = indexedTokens(data);
  setCues(indexed);
  readout(`
    <div class="bbl-steps" id="analyze-steps">${buttons}</div>
    ${body}
    <div class="idx-chips">${indexed.length
        ? indexed.map((token) => `<span class="idx-chip">${escapeHtml(token)}</span>`).join("")
        : `<span class="idx-chip is-empty">∅</span>`}</div>`);
  document.querySelectorAll("#analyze-steps [data-step]").forEach((button) => {
    button.onclick = () => {
      analyzeStep = Number(button.dataset.step);
      renderAnalyze();
    };
  });
  setAnalyzeSnippet();
}

function indexedTokens(data) {
  if (analyzeStep < 0) {
    const text = data.text ?? "";
    return text === "" ? [] : [text];
  }
  return sortTokens(data.stages?.[analyzeStep]?.tokens || []);
}

function sortTokens(tokens) {
  return [...(tokens || [])].sort((a, b) =>
      a.localeCompare(b, "en", { numeric: true, sensitivity: "base" }));
}

function tokenColumn(tokens, previous) {
  const lines = (tokens || []).map((token, i) => {
    const body = previous ? diffChars(previous[i] || "", token) : escapeHtml(token);
    return `<div class="bbl-token">${body}</div>`;
  }).join("");
  return `<div class="bbl-col">${lines || `<div class="muted">no tokens</div>`}</div>`;
}

function diffChars(from, to) {
  if (from === to) {
    return escapeHtml(to);
  }
  if (from.length === to.length) {
    return [...to].map((ch, i) => {
      const escaped = escapeHtml(ch);
      return ch === from[i] ? escaped : `<span class="delta">${escaped}</span>`;
    }).join("");
  }
  return `<span class="delta">${escapeHtml(to)}</span>`;
}

const TOKEN_PALETTE = ["#c8f27a", "#7eb6d9", "#f2cc8f", "#e5989b", "#c9a0dc", "#80cbc4", "#ffab91"];

function coloredTokens(tokens) {
  return sortTokens(tokens).map((tok, i) => {
    const color = TOKEN_PALETTE[i % TOKEN_PALETTE.length];
    return `<span class="tok-pill" style="--tok:${color}">${escapeHtml(tok)}</span>`;
  }).join(`<span class="tok-sep"> · </span>`);
}

function cardDash(value) {
  if (value == null) return "—";
  const text = String(value).trim();
  return text === "" ? "—" : text;
}

function formatBpm(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return "—";
  return Number.isInteger(n) ? n.toFixed(1) : String(n);
}

function formatYear(value) {
  const n = Number(value);
  return n > 0 ? String(n) : "—";
}

function starRow(rating) {
  const n = Math.max(0, Math.min(5, Number(rating) || 0));
  let html = `<span class="stars" aria-label="${n} of 5">`;
  for (let i = 1; i <= 5; i++) {
    html += `<i class="fa-${i <= n ? "solid" : "regular"} fa-star" aria-hidden="true"></i>`;
  }
  return html + "</span>";
}

const CAMELOT_CODES = [
  "10A", "10B", "11A", "11B", "12A", "12B",
  "1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B",
  "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B", "9A", "9B"
];

function camelotCode(keyName) {
  if (keyName == null) return null;
  const trimmed = String(keyName).trim();
  if (!trimmed) return null;
  for (const code of CAMELOT_CODES) {
    if (trimmed === code || trimmed.startsWith(code + " ")
        || trimmed.startsWith(code + "(") || trimmed.startsWith(code + "-")) {
      return code;
    }
  }
  return null;
}

function camelotBadgeClasses(keyName) {
  if (keyName == null || String(keyName).trim() === "") return null;
  const code = camelotCode(keyName);
  return code == null
      ? "camelot-badge camelot-unknown"
      : "camelot-badge camelot-" + code.toLowerCase();
}

function camelotBadgeLabel(keyName) {
  if (keyName == null || String(keyName).trim() === "") return null;
  const code = camelotCode(keyName);
  return code != null ? code : String(keyName).trim();
}

function keyBadge(key) {
  const classes = camelotBadgeClasses(key);
  if (classes == null) return "—";
  return `<span class="${classes}">${escapeHtml(camelotBadgeLabel(key))}</span>`;
}

function renderTrackCard(data) {
  const card = document.getElementById("track-card");
  if (!card) return;
  card.hidden = false;
  const art = document.getElementById("track-card-art");
  art.alt = `${data.artist || ""} — ${data.title || ""}`;
  art.style.visibility = "visible";
  art.onerror = () => {
    art.style.visibility = "hidden";
  };
  art.src = "/tracks/" + encodeURIComponent(data.id) + ".jpg";
  const titleEl = document.getElementById("track-card-title");
  titleEl.dataset.mapRoot = "title";
  titleEl.textContent = data.title || "";
  const rows = [
    ["artist", "Artist", escapeHtml(cardDash(data.artist))],
    ["genre", "Genre", escapeHtml(cardDash(data.genre))],
    ["bpm", "BPM", escapeHtml(formatBpm(data.bpm))],
    ["key", "Key", keyBadge(data.key)],
    ["rating", "Rating", starRow(data.rating)],
    ["year", "Year", escapeHtml(formatYear(data.year))],
    ["album", "Album", escapeHtml(cardDash(data.album))],
    ["label", "Label", escapeHtml(cardDash(data.label))],
    ["comment", "Comment", escapeHtml(cardDash(data.comment))]
  ];
  document.getElementById("track-card-meta").innerHTML = rows.map(([root, label, value]) =>
      `<div class="track-card-row" data-map-root="${root}"><dt>${label}</dt><dd>${value}</dd></div>`).join("");
}

function bindMapHover() {
  bindMapHoverZone(document.getElementById("track-card"), "card");
  bindMapHoverZone(document.getElementById("snippet"), "snippet");
  bindMapHoverZone(document.getElementById("readout"), "readout");
}

function bindMapHoverZone(zone, origin) {
  if (!zone) return;
  zone.onpointerover = (event) => {
    const host = event.target.closest("[data-map-root]");
    setMapHover(host && zone.contains(host) ? host.dataset.mapRoot : null, origin);
  };
  zone.onpointerleave = () => setMapHover(null);
}

function setMapHover(root, origin) {
  document.querySelectorAll("#track-card [data-map-root]").forEach((el) => {
    el.classList.toggle("is-on", Boolean(root) && el.dataset.mapRoot === root);
  });
  document.querySelectorAll("#snippet .java-line[data-map-root]").forEach((el) => {
    el.classList.toggle("is-on", Boolean(root) && el.dataset.mapRoot === root);
  });
  document.querySelectorAll("#readout .map-group[data-map-root]").forEach((el) => {
    el.classList.toggle("is-on", Boolean(root) && el.dataset.mapRoot === root);
  });
  if (!root) return;
  if (origin !== "snippet") {
    const lines = [...document.querySelectorAll("#snippet .java-line.is-on")];
    scrollIntoPanel(document.getElementById("snippet")?.closest(".panel"), lines[0], lines.at(-1));
  }
  if (origin !== "readout") {
    scrollIntoPanel(
        document.getElementById("readout"),
        document.querySelector("#readout .map-group.is-on"));
  }
  if (origin !== "card") {
    scrollIntoPanel(
        document.getElementById("track-card")?.closest(".panel"),
        document.querySelector("#track-card [data-map-root].is-on"));
  }
}

function scrollIntoPanel(panel, start, end = start) {
  if (!panel || !start) return;
  const box = panel.getBoundingClientRect();
  const pad = 10;
  const top = start.getBoundingClientRect().top;
  const bottom = (end || start).getBoundingClientRect().bottom;
  if (top < box.top + pad) {
    panel.scrollTop -= box.top + pad - top;
  } else if (bottom > box.bottom - pad) {
    panel.scrollTop += bottom - (box.bottom - pad);
    const again = start.getBoundingClientRect().top;
    if (again < box.top + pad) {
      panel.scrollTop -= box.top + pad - again;
    }
  }
}

function mapRole(field) {
  let role = escapeHtml(field.role || "");
  role = role.replace(
      /numericValue\(\) = (0x[0-9A-Fa-f]+)/,
      "numericValue() = <span class=\"ieee\">$1</span>");
  if (field.tokenized && field.tokens && field.tokens.length) {
    role += " · " + coloredTokens(field.tokens);
  }
  return role;
}

async function runMap() {
  const select = document.getElementById("map-track");
  const id = select ? select.value : "";
  const data = await getJson("/api/map" + (id ? "?id=" + encodeURIComponent(id) : ""));
  if (select && select.options.length === 0) {
    select.innerHTML = (data.picks || []).map((pick) =>
        `<option value="${escapeAttr(pick.id)}">${escapeHtml(pick.artist)} — ${escapeHtml(pick.title)}</option>`
    ).join("");
    select.onchange = () => runMap();
  }
  if (select) {
    select.value = data.id;
  }
  renderTrackCard(data);
  setCues([]);
  const groups = mapFieldGroups(data.fields);
  setMapSnippet(groups);
  readout(`
    <h3>${escapeHtml(data.artist)} — ${escapeHtml(data.title)}</h3>
    ${groups.map((group) => `
      <div class="map-group" data-map-root="${escapeAttr(group.root)}">
        ${group.fields.map((field) => `
          <div class="bucket">
            <span>${escapeHtml(field.name)} <span class="muted">${escapeHtml(field.luceneType)}</span></span>
            <span>${escapeHtml(field.value || "∅")}</span>
          </div>
          <p class="muted">${mapRole(field)}</p>
        `).join("")}
      </div>`).join("")}`);
  bindMapHover();
}

async function runIndex() {
  const term = document.getElementById("term").value;
  const field = document.getElementById("index-field").value;
  const data = await getJson(
      "/api/index?term=" + encodeURIComponent(term) + "&field=" + encodeURIComponent(field));
  setCues([data.term]);
  const shown = (data.postings || []).length;
  readout(`
    <h3>${data.numDocs} docs · ${escapeHtml(data.directory)}</h3>
    <p>${escapeHtml(data.field)}:${escapeHtml(data.term)}  df=${data.docFreq}</p>
    <h3>Posting list</h3>
    ${shown && shown < data.docFreq ? `<p class="muted">showing ${shown} of ${data.docFreq}</p>` : ""}
    ${shown ? data.postings.map((p) => `
      <div class="hit">
        <span class="score">×${p.freq}</span>
        <span>${escapeHtml(p.artist)} — ${escapeHtml(p.title)}</span>
      </div>`).join("") : `<p class="muted">no postings</p>`}`);
}

function analyzeSnippetLines(text) {
  return [
    { code: "Analyzer analyzer = new Analyzer() {" },
    { code: "  @Override" },
    { code: "  protected TokenStreamComponents createComponents(String fieldName) {" },
    { code: "    Tokenizer source = new StandardTokenizer();", step: 0 },
    { code: "    TokenStream filter = new LowerCaseFilter(source);", step: 1 },
    { code: "    filter = new ASCIIFoldingFilter(filter);", step: 2 },
    { code: "    return new TokenStreamComponents(source, filter);" },
    { code: "  }" },
    { code: "};" },
    { code: "// Analyze a text" },
    { code: `TokenStream ts = analyzer.tokenStream("title", ${javaString(text ?? "")});` }
  ];
}

function setAnalyzeSnippet() {
  const text = document.getElementById("text")?.value ?? "";
  const html = analyzeSnippetLines(text).map((line) => {
    const on = line.step === analyzeStep;
    return `<span class="java-line${on ? " is-on" : ""}">${highlightJava(line.code)}</span>`;
  }).join("");
  document.getElementById("snippet").innerHTML = html;
  document.querySelector("#snippet .java-line.is-on")?.scrollIntoView({ block: "nearest" });
}

function analyzeSnippet(text) {
  return analyzeSnippetLines(text).map((line) => line.code).join("\n");
}

function javaString(value) {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll("\"", "\\\"")}"`;
}

function javaDouble(n) {
  const s = String(n);
  return s.includes(".") ? s : s + ".0";
}

const MAP_FIELD_ORDER = [
  "id", "title", "artist", "genre", "bpm", "key", "rating", "year", "album", "label", "comment"
];

function mapFieldRoot(field) {
  if (field.luceneType === "SortedSetDocValuesFacetField") {
    return "genre";
  }
  const name = field.name || "";
  const dot = name.indexOf(".");
  return dot === -1 ? name : name.slice(0, dot);
}

function mapFieldGroups(fields) {
  const byRoot = new Map();
  for (const field of fields || []) {
    const root = mapFieldRoot(field);
    let group = byRoot.get(root);
    if (!group) {
      group = { root, fields: [] };
      byRoot.set(root, group);
    }
    group.fields.push(field);
  }
  return [...byRoot.values()].sort((a, b) => mapFieldRank(a.root) - mapFieldRank(b.root));
}

function mapFieldRank(root) {
  const index = MAP_FIELD_ORDER.indexOf(root);
  return index === -1 ? MAP_FIELD_ORDER.length : index;
}

function mapGroupComment(root) {
  switch (root) {
    case "id":
      return "stored join key back to the Track bean";
    case "title":
    case "artist":
      return `${root}: TextField is analyzed (MUST). .raw keeps the original for display. .raw.normalized is the exact FILTER.`;
    case "genre":
      return "genre: analyzed text + keyword FILTER (.raw.normalized)";
    case "album":
    case "label":
    case "comment":
      return `${root}: analyzed free text only — no keyword twin`;
    case "key":
      return "Camelot key — exact FILTER / MUST_NOT (lowercased)";
    case "bpm":
      return "numeric range / sort. numericValue() is IEEE 754 bits; read storedValue().getDoubleValue()";
    case "rating":
    case "year":
      return `${root}: numeric filter / sort`;
    default:
      return null;
  }
}

function mapSnippetLines(groups) {
  const lines = [{ code: "Document doc = new Document();" }];
  groups.forEach((group, index) => {
    if (index > 0) {
      lines.push({ code: "" });
    }
    const comment = mapGroupComment(group.root);
    if (comment) {
      lines.push({ code: `// ${comment}`, root: group.root });
    }
    for (const field of group.fields) {
      if (field.luceneType === "SortedSetDocValuesFacetField") {
        // Facet-only fields belong to the Facets chapter.
        continue;
      }
      const name = javaString(field.name);
      let code;
      if (field.luceneType === "DoubleField") {
        const n = Number(field.value);
        code = `doc.add(new DoubleField(${name}, ${Number.isFinite(n) ? javaDouble(n) : "0.0"}, Store.YES));`;
      } else if (field.luceneType === "IntField") {
        const n = Number(field.value);
        code = `doc.add(new IntField(${name}, ${Number.isFinite(n) ? n : 0}, Store.YES));`;
      } else {
        code = `doc.add(new ${field.luceneType}(${name}, ${javaString(field.value ?? "")}, Store.YES));`;
      }
      lines.push({ code, root: group.root });
    }
  });
  return lines;
}

function mapSnippet(groups) {
  return mapSnippetLines(groups).map((line) => line.code).join("\n");
}

function setMapSnippet(groups) {
  const html = mapSnippetLines(groups).map((line) => {
    const root = line.root ? ` data-map-root="${escapeAttr(line.root)}"` : "";
    return `<span class="java-line"${root}>${highlightJava(line.code)}</span>`;
  }).join("");
  document.getElementById("snippet").innerHTML = html;
}

function javaFloat(n) {
  const s = String(n);
  return (s.includes(".") ? s : s + ".0") + "f";
}

function luceneNormalize(value) {
  return String(value).normalize("NFC").toLowerCase();
}

const SEARCH_RESERVED = new Set([
  "analyzer", "bqb", "bool", "doc", "hits", "key", "keys", "q", "query",
  "searcher", "term", "text", "tokens", "ts", "writer"
]);

function searchBuilderName(token, i, used) {
  let base;
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(token) && !JAVA_KEYWORDS.has(token)) {
    base = SEARCH_RESERVED.has(token) ? `${token}_` : token;
  } else {
    base = `tok${i}`;
  }
  let name = base;
  let n = 2;
  while (used.has(name)) {
    name = `${base}${n++}`;
  }
  used.add(name);
  return name;
}

function searchIdentFrom(value, fallback, used) {
  const folded = luceneNormalize(value).replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  const candidate = /^[A-Za-z_]/.test(folded) ? folded : `k${folded}`;
  return searchBuilderName(candidate || fallback, 0, used);
}

const SEARCH_TEXT_FIELDS = [
  { field: "title", boost: 4.0, prefixBoost: 1.0 },
  { field: "artist", boost: 3.0, prefixBoost: 0.75 },
  { field: "genre", boost: 2.0, prefixBoost: 0.5 },
  { field: "album", boost: 1.5, prefixBoost: 0.375 },
  { field: "label", boost: 1.0, prefixBoost: 0.25 },
  { field: "comment", boost: 0.5, prefixBoost: 0.125 }
];

function tokenBuilderBody(token, prefix) {
  const tokenKey = `token:${token}`;
  const lines = [{ code: "BooleanQuery.Builder bqb = new BooleanQuery.Builder();", keys: [tokenKey] }];
  for (const { field, boost, prefixBoost } of SEARCH_TEXT_FIELDS) {
    lines.push({
      code: `bqb.add(new BoostQuery(new TermQuery(new Term(${javaString(field)}, ${javaString(token)})), ${javaFloat(boost)}), BooleanClause.Occur.SHOULD);`,
      keys: [`term:${field}:${token}`]
    });
    if (prefix) {
      lines.push({
        code: `bqb.add(new BoostQuery(new PrefixQuery(new Term(${javaString(field)}, ${javaString(token)})), ${javaFloat(prefixBoost)}), BooleanClause.Occur.SHOULD);`,
        keys: [`prefix:${field}:${token}`]
      });
    }
  }
  lines.push({ code: "bqb.setMinimumNumberShouldMatch(1);", keys: [tokenKey] });
  return lines;
}

const searchFoldOpen = new Set();

function searchLine(code, ...keys) {
  return { type: "line", code, keys };
}

function searchSnippetBlocks(q, tokens, genre, minus, explainDoc) {
  const terms = (tokens || []).filter(Boolean);
  const genreValue = (genre || "").trim();
  const keyValues = (minus || "").trim() ? minus.split(/\s*,\s*/).filter(Boolean) : [];
  const explain = explainDoc == null ? "hits.scoreDocs[0].doc" : String(Number(explainDoc));
  const blocks = [
    searchLine("// Open an index searcher using the same writer"),
    searchLine("IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer));"),
    searchLine("")
  ];

  if (String(q ?? "").trim()) {
    blocks.push(searchLine(`TokenStream ts = analyzer.tokenStream("title", ${javaString(q)});`));
    blocks.push(searchLine(terms.length ? `// → ${terms.join(", ")}` : "// → (no tokens)"));
    blocks.push(searchLine(""));
  }

  const used = new Set(SEARCH_RESERVED);
  const builders = terms.map((token, i) => {
    const name = searchBuilderName(token, i, used);
    const prefix = i === terms.length - 1 && token.length >= 1;
    blocks.push(searchLine(`// build query for ${token}`));
    blocks.push({
      type: "fold",
      id: name,
      keys: [`token:${token}`],
      summary: `BooleanQuery.Builder ${name} = buildQuery(${javaString(token)});`,
      body: tokenBuilderBody(token, prefix)
    });
    blocks.push(searchLine(""));
    return name;
  });

  let genreIdent = null;
  if (genreValue) {
    const normalized = luceneNormalize(genreValue);
    genreIdent = searchIdentFrom(genreValue, "genre", used);
    blocks.push(searchLine(`// Single filter on genre for ${normalized}`));
    blocks.push(searchLine(
        `Query ${genreIdent} = new TermQuery(new Term("genre.raw.normalized", ${javaString(normalized)}));`,
        "filter:genre"));
    blocks.push(searchLine(""));
  }

  let keyExpr = null;
  if (keyValues.length === 1) {
    const normalized = luceneNormalize(keyValues[0]);
    const ident = searchIdentFrom(keyValues[0], "excluded", used);
    blocks.push(searchLine(`// Filter on key ${normalized}`));
    blocks.push(searchLine(
        `Query ${ident} = new TermQuery(new Term("key.code", ${javaString(normalized)}));`,
        "mustnot:key"));
    blocks.push(searchLine(""));
    keyExpr = ident;
  } else if (keyValues.length > 1) {
    const labels = keyValues.map((key) => luceneNormalize(key)).join(" or ");
    blocks.push(searchLine(`// Filter on keys ${labels}`));
    blocks.push(searchLine("BooleanQuery.Builder keys = new BooleanQuery.Builder();", "mustnot:key"));
    keyValues.forEach((key) => {
      blocks.push(searchLine(
          `keys.add(new TermQuery(new Term("key.code", ${javaString(luceneNormalize(key))})), BooleanClause.Occur.SHOULD);`,
          "mustnot:key"));
    });
    blocks.push(searchLine("keys.setMinimumNumberShouldMatch(1);", "mustnot:key"));
    blocks.push(searchLine(""));
    keyExpr = "keys.build()";
  }

  const needsBool = builders.length > 1 || Boolean(genreIdent || keyExpr);
  if (!builders.length && !genreIdent && !keyExpr) {
    blocks.push(searchLine("Query q = new MatchAllDocsQuery();"));
  } else if (builders.length === 1 && !needsBool) {
    blocks.push(searchLine(`Query q = ${builders[0]}.build();`, `token:${terms[0]}`));
  } else {
    blocks.push(searchLine("// Combine queries in a bool query"));
    blocks.push(searchLine("BooleanQuery.Builder bool = new BooleanQuery.Builder();", "bool"));
    builders.forEach((name, i) => {
      blocks.push(searchLine(
          `bool.add(${name}.build(), BooleanClause.Occur.MUST);`,
          "bool",
          `token:${terms[i]}`));
    });
    if (!builders.length && keyExpr && !genreIdent) {
      blocks.push(searchLine("bool.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);", "bool"));
    }
    if (genreIdent) {
      blocks.push(searchLine("// Filter in"));
      blocks.push(searchLine(`bool.add(${genreIdent}, BooleanClause.Occur.FILTER);`, "bool", "filter:genre"));
    }
    if (keyExpr) {
      blocks.push(searchLine("// Filter out"));
      blocks.push(searchLine(`bool.add(${keyExpr}, BooleanClause.Occur.MUST_NOT);`, "bool", "mustnot:key"));
    }
    blocks.push(searchLine("Query q = bool.build();", "bool"));
  }

  blocks.push(searchLine(""));
  blocks.push(searchLine("// Search and retrieve the 10 first hits"));
  blocks.push(searchLine("TopDocs hits = searcher.search(q, 10);"));
  blocks.push(searchLine(""));
  blocks.push(searchLine("// Explain how the score is computed for the first hit"));
  blocks.push(searchLine(`searcher.explain(q, ${explain});`));
  return blocks;
}

function explainKeysAttr(keys) {
  return keys && keys.length ? ` data-explain-keys="${escapeAttr(keys.join(" "))}"` : "";
}

function javaLineHtml(code, keys) {
  const attr = explainKeysAttr(keys);
  if (code === "") {
    return `<span class="java-line is-blank"${attr}>\u00a0</span>`;
  }
  return `<span class="java-line"${attr}>${highlightJava(code)}</span>`;
}

function setSearchFoldOpen(fold, open) {
  const id = fold.dataset.fold;
  if (open) {
    searchFoldOpen.add(id);
  } else {
    searchFoldOpen.delete(id);
  }
  fold.classList.toggle("is-open", open);
  fold.querySelector(".java-fold-body").hidden = !open;
  const button = fold.querySelector(".java-fold-toggle");
  button.setAttribute("aria-expanded", open ? "true" : "false");
  button.setAttribute("aria-label", open ? "Collapse" : "Expand");
  button.querySelector("i").className = `fa-regular ${open ? "fa-square-minus" : "fa-square-plus"}`;
}

function setSearchSnippet(q, tokens, genre, minus, explainDoc) {
  const html = searchSnippetBlocks(q, tokens, genre, minus, explainDoc).map((block) => {
    if (block.type === "fold") {
      const open = searchFoldOpen.has(block.id);
      const icon = open ? "fa-square-minus" : "fa-square-plus";
      const label = open ? "Collapse" : "Expand";
      const body = block.body.map((line) => javaLineHtml(line.code, line.keys)).join("");
      return `<div class="java-fold${open ? " is-open" : ""}" data-fold="${escapeAttr(block.id)}"${explainKeysAttr(block.keys)}><div class="java-fold-bar"><button type="button" class="java-fold-toggle" aria-expanded="${open}" aria-label="${label}"><i class="fa-regular ${icon}" aria-hidden="true"></i></button>${javaLineHtml(block.summary, block.keys)}</div><div class="java-fold-body"${open ? "" : " hidden"}>${body}</div></div>`;
    }
    return javaLineHtml(block.code, block.keys);
  }).join("");
  document.getElementById("snippet").innerHTML = html;
  document.querySelectorAll("#snippet .java-fold-toggle").forEach((button) => {
    button.onclick = () => {
      const fold = button.closest(".java-fold");
      setSearchFoldOpen(fold, !searchFoldOpen.has(fold.dataset.fold));
    };
  });
}

function parseExplainKeys(value) {
  return (value || "").split(/\s+/).filter(Boolean);
}

function bindExplainHover() {
  bindExplainHoverZone(document.getElementById("snippet"), "snippet");
  bindExplainHoverZone(document.getElementById("readout"), "readout");
}

function bindExplainHoverZone(zone, origin) {
  if (!zone) return;
  zone.onpointerover = (event) => {
    const host = event.target.closest("[data-explain-keys]");
    setExplainHover(host && zone.contains(host) ? parseExplainKeys(host.dataset.explainKeys) : [], origin);
  };
  zone.onpointerleave = () => setExplainHover([]);
}

function setExplainHover(keys, origin) {
  const wanted = new Set(keys);
  document.querySelectorAll("#snippet [data-explain-keys], #readout [data-explain-keys]").forEach((el) => {
    const have = parseExplainKeys(el.dataset.explainKeys);
    el.classList.toggle("is-on", wanted.size > 0 && have.some((key) => wanted.has(key)));
  });
  if (!wanted.size) return;
  if (origin !== "snippet") {
    const lines = [...document.querySelectorAll("#snippet .java-line.is-on")];
    lines.forEach((line) => {
      const fold = line.closest(".java-fold");
      if (fold && line.closest(".java-fold-body")) {
        setSearchFoldOpen(fold, true);
      }
    });
    scrollIntoPanel(document.getElementById("snippet")?.closest(".panel"), lines[0], lines.at(-1));
  }
  if (origin !== "readout") {
    const lines = [...document.querySelectorAll("#readout .explain-line.is-on")];
    scrollIntoPanel(document.getElementById("readout"), lines[0], lines.at(-1));
  }
}

function explainTreeHtml(node, depth = 0) {
  if (!node) return "";
  const kids = (node.details || []).map((child) => explainTreeHtml(child, depth + 1)).join("");
  const value = node.value == null ? "" : String(node.value);
  return `<div class="explain-line"${explainKeysAttr(node.keys)} style="--depth:${depth}"><span class="explain-value">${escapeHtml(value)}</span> = ${escapeHtml(node.description)}</div>${kids}`;
}

function setSnippet(source) {
  document.getElementById("snippet").innerHTML = highlightJava(source);
}

async function runSearch(explainDoc) {
  const q = document.getElementById("q").value;
  const genre = document.getElementById("genre").value.trim();
  const minus = document.getElementById("minus").value.trim();
  const filters = {};
  const mustNots = {};
  if (genre) filters.genre = [genre];
  if (minus) mustNots.key = minus.split(/\s*,\s*/).filter(Boolean);
  const data = await getJson("/api/search", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      q,
      filters,
      mustNots,
      explainDoc: explainDoc ?? null
    })
  });
  setSearchSnippet(q, data.tokens, genre, minus, explainDoc);
  setCues(data.tokens);
  const explained = data.hits.find((hit) => hit.explainTree || hit.explain);
  const explainHtml = explained?.explainTree
      ? `<h3>Explanation · doc ${explained.luceneDoc}</h3><div class="explain">${explainTreeHtml(explained.explainTree)}</div>`
      : explained?.explain
          ? `<h3>Explanation · doc ${explained.luceneDoc}</h3><pre class="explain">${escapeHtml(explained.explain)}</pre>`
          : "";
  readout(`
    <h3>${data.total} hits</h3>
    <p>${escapeHtml(data.query)}</p>
    ${(data.hits || []).map((hit) => `
      <div class="hit" data-doc="${hit.luceneDoc}">
        <span class="score">${hit.score.toFixed(2)}</span>
        <span>${escapeHtml(hit.artist)} — ${escapeHtml(hit.title)}
          <span class="muted"> · ${escapeHtml(hit.genre || "—")} · ${escapeHtml(hit.key || "—")}</span>
        </span>
      </div>`).join("") || `<p class="muted">no hits</p>`}
    ${explainHtml}`);
  document.querySelectorAll(".hit[data-doc]").forEach((row) => {
    row.onclick = () => runSearch(Number(row.dataset.doc));
  });
  bindExplainHover();
}

let suggestState = { prefix: "club", hits: [], index: 0 };

async function runSuggest() {
  const prefix = document.getElementById("prefix").value;
  const data = await getJson("/api/suggest", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ prefix })
  });
  suggestState = { prefix, hits: data.hits || [], index: 0 };
  setCues([prefix]);
  renderSuggest();
}

function suggestSnippetLines() {
  const prefix = suggestState.prefix ?? "";
  const hit = suggestState.hits[suggestState.index];
  const index = hit ? suggestState.index : 0;
  const lines = [
    { code: "// Same analyzer as page 1. Second RAM directory, not the inverted index." },
    { code: "Directory suggestionDirectory = new ByteBuffersDirectory();", part: "ctor" },
    { code: "AnalyzingInfixSuggester suggester = new AnalyzingInfixSuggester(", part: "ctor" },
    { code: "        suggestionDirectory, analyzer);", part: "ctor" },
    { code: "" },
    { code: "suggester.build(new TrackSuggestionInputIterator(tracks.values()));", part: "build" },
    { code: "" },
    { code: "List<Lookup.LookupResult> matches =", part: "lookup" },
    { code: `        suggester.lookup(${javaString(prefix)}, Set.of(), 10, true, true);`, part: "lookup" }
  ];
  if (!hit) {
    return lines;
  }
  lines.push({ code: "" });
  lines.push({ code: `Lookup.LookupResult match = matches.get(${index});`, part: "lookup" });
  lines.push({ code: `String text = match.key.toString(); // ${javaString(hit.text)}`, part: "key" });
  lines.push({ code: `String field = match.payload.utf8ToString(); // ${javaString(hit.field)}`, part: "payload" });
  lines.push({ code: "String highlight = match.highlightKey.toString();", part: "highlight" });
  lines.push({ code: `// ${javaString(hit.highlight)}`, part: "highlight" });
  if (hit.field === "genre") {
    lines.push({
      code: `// genre chip → FILTER TermQuery(genre.raw.normalized, ${javaString(luceneNormalize(hit.text))})`,
      part: "payload"
    });
  } else {
    lines.push({
      code: `// ${hit.field} chip → free text, not a FILTER`,
      part: "payload"
    });
  }
  return lines;
}

function setSuggestSnippet() {
  const html = suggestSnippetLines().map((line) => {
    if (line.code === "") {
      return javaLineHtml("");
    }
    const part = line.part ? ` data-suggest-part="${escapeAttr(line.part)}"` : "";
    return `<span class="java-line"${part}>${highlightJava(line.code)}</span>`;
  }).join("");
  document.getElementById("snippet").innerHTML = html;
}

function renderSuggest() {
  const prefix = suggestState.prefix ?? "";
  const hits = suggestState.hits;
  const lookupCall = `suggester.lookup(${javaString(prefix)}, Set.of(), 10, true, true)`;
  const rows = hits.map((hit, i) => `
      <div class="suggest-hit" data-suggest-index="${i}" data-suggest-part="row">
        <span class="muted" data-suggest-part="lookup">matches.get(${i})</span>
        <span data-suggest-part="highlight">${safeHighlight(hit.highlight)}</span>
        <span class="suggest-field" data-suggest-part="payload"><i class="fa-solid ${suggestIcon(hit.field)}" aria-hidden="true"></i>${escapeHtml(hit.field)}</span>
      </div>`).join("");
  setSuggestSnippet();
  readout(`
    <div class="suggest-step" data-suggest-part="ctor">
      <span class="muted">new</span>
      AnalyzingInfixSuggester(new ByteBuffersDirectory(), analyzer)
    </div>
    <div class="suggest-step" data-suggest-part="build">
      <span class="muted">build</span>
      InputIterator · next()=text · payload()=field
    </div>
    <div class="bbl-down">↓</div>
    <div class="suggest-step" data-suggest-part="lookup">
      <span class="muted">lookup</span>
      ${escapeHtml(lookupCall)}
    </div>
    <div class="bbl-down">↓</div>
    <h3>List&lt;Lookup.LookupResult&gt; · ${hits.length}</h3>
    ${hits.length ? `
      <div class="suggest-head">
        <span>match</span>
        <span>highlightKey</span>
        <span>field</span>
      </div>
      ${rows}` : `<p class="muted">nothing in the dictionary</p>`}`);
  bindSuggestHover();
  setSuggestHover(hits.length ? "lookup" : "ctor");
}

function bindSuggestHover() {
  bindSuggestHoverZone(document.getElementById("snippet"), "snippet");
  bindSuggestHoverZone(document.getElementById("readout"), "readout");
}

function bindSuggestHoverZone(zone, origin) {
  if (!zone) return;
  zone.onpointerover = (event) => {
    const host = event.target.closest("[data-suggest-part]");
    if (!host || !zone.contains(host)) {
      setSuggestHover(null, origin);
      return;
    }
    const hit = host.closest(".suggest-hit");
    if (hit) {
      const index = Number(hit.dataset.suggestIndex);
      if (Number.isFinite(index) && index !== suggestState.index) {
        suggestState.index = index;
        setSuggestSnippet();
      }
    }
    setSuggestHover(host.dataset.suggestPart, origin);
  };
  zone.onpointerleave = () => setSuggestHover(null);
}

function suggestLineActive(linePart, hoverPart) {
  if (!hoverPart || !linePart) {
    return false;
  }
  if (hoverPart === linePart) {
    return true;
  }
  if (hoverPart === "row") {
    return linePart === "lookup" || linePart === "key"
        || linePart === "payload" || linePart === "highlight";
  }
  if (hoverPart === "highlight") {
    return linePart === "highlight" || linePart === "key";
  }
  return false;
}

function setSuggestHover(part, origin) {
  document.querySelectorAll("#snippet .java-line[data-suggest-part]").forEach((el) => {
    el.classList.toggle("is-on", suggestLineActive(el.dataset.suggestPart, part));
  });
  document.querySelectorAll("#readout .suggest-step").forEach((el) => {
    el.classList.toggle("is-on", Boolean(part) && el.dataset.suggestPart === part);
  });
  document.querySelectorAll("#readout .suggest-hit").forEach((el) => {
    const hitPart = part === "row" || part === "key" || part === "payload"
        || part === "highlight" || part === "lookup";
    el.classList.toggle(
        "is-on",
        hitPart && Number(el.dataset.suggestIndex) === suggestState.index);
  });
  if (!part) {
    return;
  }
  if (origin !== "snippet") {
    const lines = [...document.querySelectorAll("#snippet .java-line.is-on")];
    scrollIntoPanel(document.getElementById("snippet")?.closest(".panel"), lines[0], lines.at(-1));
  }
  if (origin !== "readout") {
    const hit = document.querySelector("#readout .suggest-hit.is-on");
    const step = document.querySelector("#readout .suggest-step.is-on");
    scrollIntoPanel(document.getElementById("readout"), hit || step);
  }
}

async function runHighlight() {
  const q = document.getElementById("hq").value;
  const data = await getJson("/api/search", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ q, filters: {}, mustNots: {} })
  });
  setCues(data.tokens || []);
  setSnippet(highlightSnippet(q, data.tokens || []));
  readout(`
    <h3>${data.total} hits</h3>
    <p class="muted">${escapeHtml(data.query)}</p>
    ${(data.hits || []).map((hit) => `
      <div class="hit">
        <span class="score">${hit.score.toFixed(2)}</span>
        <div>${highlightFieldsHtml(hit)}</div>
      </div>`).join("") || `<p class="muted">no hits</p>`}`);
}

function highlightSnippet(q, tokens) {
  const tokenList = tokens.length ? tokens.map((token) => javaString(token)).join(", ") : "";
  return `Query q = TrackLuceneQueryBuilder.buildStructured(${javaString(q)}, Map.of(), Map.of());
TopDocs hits = searcher.search(q, 25);
// tokens: ${tokenList || "match-all"}

UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
    .withMaxLength(10_000)
    .withBreakIterator(WholeBreakIterator::new)
    .build();
Map<String, String[]> hl = highlighter.highlightFields(
        new String[]{"title", "artist", "genre", "album", "label", "comment"},
        q, hits);`;
}

function highlightFieldsHtml(hit) {
  const fields = hit.highlights || {};
  const always = ["title", "artist", "genre"];
  const extra = ["album", "label", "comment"];
  const names = [
    ...always.filter((field) => fields[field]),
    ...extra.filter((field) => (fields[field] || "").includes("<b>"))
  ];
  if (!names.length) {
    return `<span>${escapeHtml(hit.artist)} — ${escapeHtml(hit.title)}</span>`;
  }
  return names.map((field) => {
    const snippet = fields[field];
    const matched = snippet.includes("<b>");
    return `<div class="hl-field${matched ? " is-on" : ""}">
      <span class="muted">${escapeHtml(field)}</span>
      <span class="hl">${safeHighlight(snippet)}</span>
    </div>`;
  }).join("");
}

function safeHighlight(html) {
  return escapeHtml(html || "")
      .replaceAll("&lt;b&gt;", "<b>")
      .replaceAll("&lt;/b&gt;", "</b>");
}

async function runFacets() {
  const q = document.getElementById("fq").value;
  const drillGenre = document.getElementById("drill").value.trim();
  const data = await getJson("/api/facets", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ q, drillGenre })
  });
  setCues(q ? q.split(/\s+/) : []);
  setSnippet(facetsSnippet(data.rewrite));
  readout(`
    <h3>FacetsConfig.build</h3>
    ${facetRewriteHtml(data.rewrite)}
    <h3>${data.drillSideways ? "DrillSideways" : "FacetsCollector"}</h3>
    <p class="muted">${escapeHtml(data.query)}</p>
    ${data.dims.filter((dim) => dim.name !== "key").map((dim) => `
      <h3>${dim.emoji} ${escapeHtml(dim.name)}</h3>
      ${dim.buckets.map((bucket) => `
        <div class="bucket${drillGenre && bucket.label === drillGenre ? " is-on" : ""}">
          <span>${facetBucketLabel(dim.name, bucket.label)}</span>
          <span>${bucket.count}</span>
        </div>`).join("")}
    `).join("")}`);
}

function facetRewriteHtml(rewrite) {
  const before = rewrite?.before || [];
  const after = rewrite?.after || [];
  if (!before.length && !after.length) {
    return "";
  }
  const row = (field) => `
    <div class="bucket">
      <span>${escapeHtml(field.name)} <span class="muted">${escapeHtml(field.luceneType)}</span></span>
      <span>${escapeHtml(field.value || "∅")}</span>
    </div>
    <p class="muted">${escapeHtml(field.role || "")}</p>`;
  return `
    <div class="facet-rewrite">
      <p class="muted">Ultra Naté · SortedSetDocValuesFacetField → indexed $facets</p>
      ${before.map(row).join("")}
      <div class="bbl-down">↓</div>
      ${after.map(row).join("")}
    </div>`;
}

function facetsSnippet(_rewrite) {
  const lines = [
    "FacetsConfig facetsConfig = new FacetsConfig();",
    "",
    "// We update the mapper as categorical dims need a facet field",
    "doc.add(new SortedSetDocValuesFacetField(\"genre\", \"Club\"));",
    "// This produces behind the scene:",
    "// doc.add(new SortedSetDocValuesField(\"$facets\", new BytesRef(\"genre\\u001FClub\")))",
    "// doc.add(new StringField(\"$facets\", \"genre\\u001FClub\", Store.NO))",
    "// doc.add(new StringField(\"$facets\", \"genre\", Store.NO))",
    "",
    "// Index track #255465792 with the facet fields",
    "Document doc255465792 = mapper.toDocument(Tracks.trackFrom(255465792));",
    "writer.addDocument(facetsConfig.build(doc255465792));",
    "",
    "FacetsCollector fc = FacetsCollectorManager.search(searcher, q, 1, manager)",
    "    .facetsCollector();",
    "Facets genres = new SortedSetDocValuesFacetCounts(state, fc);",
    "Facets bpm = new DoubleRangeFacetCounts(\"bpm\", fc, bpmRanges());",
    "new DrillSideways(searcher, config, state).search(drillDown, 1);"
  ];
  return lines.join("\n");
}

let demoState = { chips: [], suggest: [], open: false, gen: 0 };
let demoEngine = "lucene";
let esStatus = { url: "http://localhost:9200/", apiKeySet: false, ready: false, error: null, docs: 0 };

function demoBackend() {
  return demoEngine === "elasticsearch" && esStatus.ready ? "elasticsearch" : "lucene";
}

function demoApi(path) {
  const backend = demoBackend();
  return backend === "elasticsearch" ? `${path}?backend=elasticsearch` : path;
}

function setDemoBackend(backend) {
  demoEngine = backend === "elasticsearch" && esStatus.ready ? "elasticsearch" : "lucene";
  const host = document.getElementById("demo-backend");
  if (host) {
    host.dataset.backend = demoEngine;
    host.querySelectorAll("[data-backend]").forEach((button) => {
      const on = button.dataset.backend === demoEngine;
      button.classList.toggle("is-on", on);
      button.setAttribute("aria-pressed", on ? "true" : "false");
    });
    const esButton = host.querySelector("[data-backend=elasticsearch]");
    if (esButton) {
      esButton.classList.toggle("is-wait", !esStatus.ready);
      esButton.title = esStatus.ready
          ? "Search with Elasticsearch"
          : "Configure Elasticsearch";
    }
  }
  syncDemoEngine();
}

function syncDemoEngine() {
  const backend = demoBackend();
  const lcdTitle = document.querySelector(".lcd h2");
  if (lcdTitle) {
    lcdTitle.textContent = backend === "elasticsearch" ? "Elasticsearch" : "Lucene playground";
  }
}

function demoClauses() {
  const filters = {};
  const mustNots = {};
  for (const chip of demoState.chips) {
    const target = chip.mode === "out" ? mustNots : filters;
    (target[chip.dim] ||= []).push(chip.value);
  }
  return { filters, mustNots };
}

function demoChipIndex(dim, value) {
  return demoState.chips.findIndex((chip) => chip.dim === dim && chip.value === value);
}

function addDemoChip(dim, value) {
  if (demoChipIndex(dim, value) >= 0) {
    return;
  }
  demoState.chips.push({ dim, value, mode: "in" });
}

function toggleDemoChip(dim, value) {
  const index = demoChipIndex(dim, value);
  if (index < 0) {
    return;
  }
  const chip = demoState.chips[index];
  chip.mode = chip.mode === "in" ? "out" : "in";
}

function removeDemoChip(dim, value) {
  demoState.chips = demoState.chips.filter((chip) => !(chip.dim === dim && chip.value === value));
}

function suggestType(field) {
  return field === "title" ? "track" : field;
}

function suggestIcon(field) {
  switch (suggestType(field)) {
    case "genre": return "fa-tag";
    case "artist": return "fa-user";
    default: return "fa-music";
  }
}

function bindDemo() {
  const input = document.getElementById("demo-q");
  const box = document.getElementById("demo-suggest");
  let timer;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      runDemoSuggest();
      runDemo();
    }, 160);
  });
  input.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      hideDemoSuggest();
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      hideDemoSuggest();
      runDemo();
    }
  });
  input.addEventListener("blur", () => {
    setTimeout(hideDemoSuggest, 120);
  });
  box.addEventListener("mousedown", (event) => event.preventDefault());
  box.addEventListener("click", (event) => {
    const row = event.target.closest("[data-suggest-text]");
    if (!row) {
      return;
    }
    pickDemoSuggestion(row.dataset.suggestField, row.dataset.suggestText);
  });
  document.getElementById("demo-chips").addEventListener("click", (event) => {
    const remove = event.target.closest("[data-chip-remove]");
    const chip = event.target.closest("[data-chip-dim]");
    if (remove && chip) {
      event.preventDefault();
      removeDemoChip(chip.dataset.chipDim, chip.dataset.chipValue);
      renderDemoChips();
      runDemo();
      return;
    }
    if (chip) {
      toggleDemoChip(chip.dataset.chipDim, chip.dataset.chipValue);
      renderDemoChips();
      runDemo();
    }
  });
  document.getElementById("demo-facets").addEventListener("click", (event) => {
    const slot = event.target.closest("[data-camelot-key]");
    if (slot) {
      toggleCamelotKey(slot.dataset.camelotKey, slot.classList.contains("is-empty"));
      return;
    }
    const bucket = event.target.closest("[data-facet-dim]");
    if (!bucket) {
      return;
    }
    addDemoChip(bucket.dataset.facetDim, bucket.dataset.facetValue);
    renderDemoChips();
    runDemo();
  });
  document.getElementById("demo-facets").addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") {
      return;
    }
    const slot = event.target.closest("[data-camelot-key]");
    if (!slot) {
      return;
    }
    event.preventDefault();
    toggleCamelotKey(slot.dataset.camelotKey, slot.classList.contains("is-empty"));
  });
}

function pickDemoSuggestion(field, text) {
  if (field === "genre" || field === "artist") {
    addDemoChip(field, text);
    document.getElementById("demo-q").value = "";
  } else {
    document.getElementById("demo-q").value = text;
  }
  hideDemoSuggest();
  renderDemoChips();
  runDemo();
}

function hideDemoSuggest() {
  demoState.open = false;
  demoState.suggest = [];
  const box = document.getElementById("demo-suggest");
  if (box) {
    box.hidden = true;
    box.innerHTML = "";
  }
  document.querySelector(".demo-search")?.classList.remove("is-open");
}

async function runDemoSuggest() {
  const input = document.getElementById("demo-q");
  const box = document.getElementById("demo-suggest");
  if (!input || !box) {
    return;
  }
  const prefix = input.value.trim();
  if (!prefix) {
    hideDemoSuggest();
    return;
  }
  const data = await getJson(demoApi("/api/suggest"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ prefix })
  });
  if (document.getElementById("demo-q")?.value.trim() !== prefix) {
    return;
  }
  demoState.suggest = data.hits || [];
  demoState.open = demoState.suggest.length > 0;
  box.hidden = !demoState.open;
  input.closest(".demo-search")?.classList.toggle("is-open", demoState.open);
  box.innerHTML = demoState.suggest.map((hit) => {
    const kind = suggestType(hit.field);
    return `
    <li>
      <button type="button" class="demo-suggest-hit" data-suggest-field="${escapeAttr(hit.field)}" data-suggest-text="${escapeAttr(hit.text)}">
        <i class="fa-solid ${suggestIcon(hit.field)}" aria-hidden="true"></i>
        <span class="demo-suggest-text">${safeHighlight(hit.highlight)}</span>
        <span class="demo-suggest-type">${escapeHtml(kind)}</span>
      </button>
    </li>`;
  }).join("");
}

function renderDemoChips() {
  const host = document.getElementById("demo-chips");
  if (!host) {
    return;
  }
  if (!demoState.chips.length) {
    host.innerHTML = "";
    return;
  }
  host.innerHTML = demoState.chips.map((chip) => {
    const mode = chip.mode === "out" ? "filter out" : "filter in";
    return `
      <button type="button" class="demo-chip is-${chip.mode}" data-chip-dim="${escapeAttr(chip.dim)}" data-chip-value="${escapeAttr(chip.value)}" aria-label="${escapeAttr(chip.dim + ": " + chip.value + ", " + mode + ". Click to invert.")}">
        <span class="demo-chip-mode">${chip.mode === "out" ? "−" : "+"}</span>
        <span class="demo-chip-dim">${escapeHtml(chip.dim)}</span>
        <span class="demo-chip-value">${facetBucketLabel(chip.dim, chip.value)}</span>
        <span class="demo-chip-remove" data-chip-remove="1" aria-label="Remove filter">×</span>
      </button>`;
  }).join("");
}

async function runDemo() {
  const input = document.getElementById("demo-q");
  if (!input) {
    return;
  }
  const q = input.value;
  const { filters, mustNots } = demoClauses();
  const gen = ++demoState.gen;
  try {
    const [search, facets] = await Promise.all([
      getJson(demoApi("/api/search"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ q, filters, mustNots })
      }),
      getJson(demoApi("/api/facets"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ q, filters, mustNots, drillGenre: "" })
      })
    ]);
    if (gen !== demoState.gen) {
      return;
    }
    renderDemoChips();
    renderDemoFacets(facets.dims || []);
    renderDemoHits(search);
    setDemoReadout(search, facets);
    setCues(search.tokens || []);
    setSnippet(demoSnippet(q, search, facets));
  } catch (error) {
    if (gen !== demoState.gen) {
      return;
    }
    readout(`<p class="muted">${escapeHtml(error.message)}</p>`);
  }
}

function demoSnippet(q, search, facets) {
  if (demoBackend() === "elasticsearch") {
    return [
      "client.search(s -> s",
      "        .index(\"tracks\")",
      "        .query(q -> q.multiMatch(mm -> mm",
      "                .query(" + javaString(q) + ")",
      "                .type(TextQueryType.BoolPrefix)",
      "                .operator(Operator.And)",
      "                .fields(\"title^4\", \"artist^3\", \"genre^2\"))),",
      "        Track.class);"
    ].join("\n");
  }
  const collector = facets.drillSideways
      ? "new DrillSideways(searcher, config, state).search(drillDown, 1);"
      : "FacetsCollectorManager.search(searcher, q, 1, manager).facetsCollector();";
  return [
    "Query q = TrackLuceneQueryBuilder.buildStructured(",
    "        " + javaString(q) + ", filters, mustNots);",
    "// " + search.query,
    "TopDocs hits = searcher.search(q, 25);",
    "UnifiedHighlighter.builder(searcher, analyzer).build()",
    "        .highlightFields(new String[]{\"title\", \"artist\", \"genre\"}, q, hits);",
    collector
  ].join("\n");
}

function toggleCamelotKey(value, empty) {
  if (empty) {
    return;
  }
  if (demoChipIndex("key", value) >= 0) {
    removeDemoChip("key", value);
  } else {
    addDemoChip("key", value);
  }
  renderDemoChips();
  runDemo();
}

function facetBucketLabel(dim, value) {
  if (dim === "rating") {
    return starRow(value);
  }
  if (dim === "key") {
    return keyBadge(value);
  }
  return escapeHtml(value);
}

function polar(cx, cy, r, degFromTop) {
  const a = (degFromTop - 90) * Math.PI / 180;
  return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
}

function annularPath(cx, cy, innerR, outerR, startDeg, endDeg) {
  const pt = (r, d) => polar(cx, cy, r, d);
  const [x0, y0] = pt(outerR, startDeg);
  const [x1, y1] = pt(outerR, endDeg);
  const [x2, y2] = pt(innerR, endDeg);
  const [x3, y3] = pt(innerR, startDeg);
  const large = endDeg - startDeg > 180 ? 1 : 0;
  const n = (value) => value.toFixed(2);
  return `M${n(x0)} ${n(y0)} A${outerR} ${outerR} 0 ${large} 1 ${n(x1)} ${n(y1)} L${n(x2)} ${n(y2)} A${innerR} ${innerR} 0 ${large} 0 ${n(x3)} ${n(y3)} Z`;
}

function renderCamelotWheel(dim) {
  const byLabel = Object.fromEntries((dim.buckets || []).map((bucket) => [bucket.label, bucket]));
  const cx = 100;
  const cy = 100;
  const rings = [
    { mode: "B", inner: 68, outer: 98 },
    { mode: "A", inner: 36, outer: 66 }
  ];
  const slots = [];
  for (const ring of rings) {
    for (let n = 1; n <= 12; n++) {
      const label = `${n}${ring.mode}`;
      const bucket = byLabel[label] || { label, count: 0 };
      const start = (n - 1) * 30 - 15 + 0.4;
      const end = (n - 1) * 30 + 15 - 0.4;
      const [lx, ly] = polar(cx, cy, (ring.inner + ring.outer) / 2, (n - 1) * 30);
      const index = demoChipIndex("key", label);
      const mode = index < 0 ? "" : demoState.chips[index].mode;
      const empty = Number(bucket.count) === 0;
      const classes = [
        "camelot-slot",
        `camelot-${label.toLowerCase()}`,
        empty ? "is-empty" : "",
        mode ? `is-${mode}` : ""
      ].filter(Boolean).join(" ");
      const title = `${label} · ${bucket.count}`;
      const pressed = mode === "in" ? "true" : "false";
      slots.push(`
        <g class="${classes}" data-camelot-key="${escapeAttr(label)}" ${empty
          ? `aria-disabled="true"`
          : `role="button" tabindex="0" aria-pressed="${pressed}"`} aria-label="${escapeAttr(title)}">
          <title>${escapeHtml(title)}</title>
          <path class="camelot-wedge" d="${annularPath(cx, cy, ring.inner, ring.outer, start, end)}"></path>
          <text class="camelot-label${ring.mode === "A" ? " is-inner" : ""}" x="${lx.toFixed(1)}" y="${ly.toFixed(1)}">${escapeHtml(label)}</text>
        </g>`);
    }
  }
  return `
    <section class="camelot-wheel-wrap">
      <h3>${dim.emoji} ${escapeHtml(dim.name)}</h3>
      <svg class="camelot-wheel" viewBox="0 0 200 200" aria-label="Camelot key filter">
        ${slots.join("")}
        <circle class="camelot-hub" cx="100" cy="100" r="32"></circle>
      </svg>
    </section>`;
}

function renderDemoFacets(dims) {
  const host = document.getElementById("demo-facets");
  if (!host) {
    return;
  }
  const keyDim = (dims || []).find((dim) => dim.name === "key");
  const others = (dims || []).filter((dim) => dim.name !== "key");
  host.innerHTML = `${others.map((dim) => `
    <section>
      <h3>${dim.emoji} ${escapeHtml(dim.name)}</h3>
      ${(dim.buckets || []).map((bucket) => {
        const index = demoChipIndex(dim.name, bucket.label);
        const mode = index < 0 ? "" : demoState.chips[index].mode;
        const empty = Number(bucket.count) === 0 ? " is-zero" : "";
        return `
        <button type="button" class="demo-bucket${mode ? ` is-${mode}` : ""}${empty}" data-facet-dim="${escapeAttr(dim.name)}" data-facet-value="${escapeAttr(bucket.label)}">
          <span>${facetBucketLabel(dim.name, bucket.label)}</span>
          <span>${bucket.count}</span>
        </button>`;
      }).join("")}
    </section>`).join("")}${keyDim ? renderCamelotWheel(keyDim) : ""}`;
}

function renderDemoHits(search) {
  const total = document.getElementById("demo-total");
  const body = document.getElementById("demo-hits");
  if (!total || !body) {
    return;
  }
  const hits = search.hits || [];
  total.textContent = hits.length
      ? `${search.total} hits in ${search.tookMs ?? 0} ms · showing ${hits.length}`
      : `no hits in ${search.tookMs ?? 0} ms`;
  body.innerHTML = hits.map((hit) => `
    <tr>
      <td>${safeHighlight(hit.highlights?.title || hit.title)}</td>
      <td>${safeHighlight(hit.highlights?.artist || hit.artist)}</td>
      <td>${safeHighlight(hit.highlights?.genre || hit.genre || "—")}</td>
      <td>${starRow(hit.rating)}</td>
      <td>${keyBadge(hit.key)}</td>
    </tr>`).join("");
}

function setDemoReadout(search, facets) {
  const engine = demoBackend() === "elasticsearch" ? "Elasticsearch" : "Lucene";
  readout(`
    <h3>${search.total} hits</h3>
    <p class="muted">${escapeHtml(engine)}</p>
    <pre class="demo-query">${escapeHtml(search.query || "")}</pre>`);
}

function show(name) {
  const mixer = document.querySelector(".mixer");
  const leavingDemo = mixer.classList.contains("is-demo") && name !== "demo";
  const chapter = chapters[name];
  document.getElementById("snippet-kicker").textContent = chapter.kicker;
  setSnippet(chapter.snippet);
  document.getElementById("deck-title").textContent = chapter.title;
  document.getElementById("deck-lede").textContent = chapter.lede;
  document.querySelectorAll(".chapters [data-chapter]").forEach((button) => {
    button.classList.toggle("is-on", button.dataset.chapter === name);
  });
  mixer.classList.toggle("is-analyze", name === "analyze");
  mixer.classList.toggle("is-map", name === "map");
  mixer.classList.toggle("is-demo", name === "demo");
  chapter.render(document.getElementById("controls"));
  if (name === "demo") {
    setZoneZoom("deck");
  } else if (leavingDemo) {
    setZoneZoom("");
  }
}

document.querySelectorAll(".chapters [data-chapter]").forEach((button) => {
  button.onclick = () => show(button.dataset.chapter);
});

function applyEsStatus(status) {
  esStatus = status || esStatus;
  document.getElementById("es-settings")?.classList.toggle("is-ready", !!esStatus.ready);
  const url = document.getElementById("es-url");
  if (url && document.activeElement !== url) {
    url.value = esStatus.url || "http://localhost:9200/";
  }
  const line = document.getElementById("es-status");
  if (line) {
    line.classList.remove("is-ok", "is-err");
    if (esStatus.ready) {
      line.classList.add("is-ok");
      line.textContent = `Indexed ${esStatus.docs} tracks`;
    } else if (esStatus.error) {
      line.classList.add("is-err");
      line.textContent = esStatus.error;
    } else {
      line.textContent = "Not connected — Lucene still runs the Demo.";
    }
  }
  setDemoBackend(demoEngine);
}

function bindEsSettings() {
  const dialog = document.getElementById("es-dialog");
  const form = document.getElementById("es-form");
  const gear = document.getElementById("es-settings");
  const close = document.getElementById("es-close");
  if (!dialog || !form || !gear) {
    return;
  }
  gear.addEventListener("click", () => {
    applyEsStatus(esStatus);
    document.getElementById("es-api-key").value = "";
    document.getElementById("es-api-key").placeholder = esStatus.apiKeySet
        ? "unchanged — paste a new key to replace"
        : "paste start-local API key";
    dialog.showModal();
  });
  close?.addEventListener("click", () => dialog.close());
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const line = document.getElementById("es-status");
    if (line) {
      line.classList.remove("is-ok", "is-err");
      line.textContent = "Connecting and indexing…";
    }
    try {
      const status = await getJson("/api/elasticsearch", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          url: document.getElementById("es-url").value,
          apiKey: document.getElementById("es-api-key").value
        })
      });
      applyEsStatus(status);
      if (status.ready) {
        setDemoBackend("elasticsearch");
        runDemo();
      }
    } catch (error) {
      if (line) {
        line.classList.add("is-err");
        line.textContent = error.message;
      }
    }
  });
}

getJson("/api/elasticsearch").then(applyEsStatus).catch(() => {});

getJson("/api/meta").then((meta) => {
  document.getElementById("meta").textContent =
      `${meta.numDocs} docs · ${meta.directory} · size in heap ${meta.heapSize} · built in ${meta.builtInMs} ms`;
}).catch((error) => {
  document.getElementById("meta").textContent = error.message;
});

show(new URLSearchParams(location.search).get("chapter") in chapters
    ? new URLSearchParams(location.search).get("chapter")
    : "analyze");

bindColumnResize();
bindZoneZoom();
bindEsSettings();

function setZoneZoom(zone) {
  const mixer = document.querySelector(".mixer");
  const buttons = document.querySelectorAll(".zoom");
  const labels = { snippet: "Java", deck: "chapter", lcd: "Lucene playground" };
  if (!mixer) {
    return;
  }
  mixer.classList.toggle("is-max-snippet", zone === "snippet");
  mixer.classList.toggle("is-max-deck", zone === "deck");
  mixer.classList.toggle("is-max-lcd", zone === "lcd");
  mixer.dataset.max = zone || "";
  buttons.forEach((button) => {
    const on = zone === button.dataset.zone;
    const icon = button.querySelector("i");
    icon.classList.toggle("fa-expand", !on);
    icon.classList.toggle("fa-compress", on);
    button.setAttribute(
        "aria-label",
        `${on ? "Reduce" : "Maximize"} ${labels[button.dataset.zone]}`);
  });
}

function bindZoneZoom() {
  const mixer = document.querySelector(".mixer");
  document.querySelectorAll(".zoom").forEach((button) => {
    button.onclick = () => {
      setZoneZoom(mixer.dataset.max === button.dataset.zone ? "" : button.dataset.zone);
    };
  });
}

function bindColumnResize() {
  const mixer = document.querySelector(".mixer");
  const handle = document.getElementById("col-split");
  if (!mixer || !handle) {
    return;
  }
  const stored = localStorage.getItem("playground-stage-width");
  if (stored) {
    mixer.style.setProperty("--stage-width", stored);
  }

  const apply = (clientX) => {
    const rect = mixer.getBoundingClientRect();
    const min = 280;
    const max = Math.max(min + 16, rect.width - 320);
    const width = Math.round(Math.min(max, Math.max(min, clientX - rect.left)));
    const value = `${width}px`;
    mixer.style.setProperty("--stage-width", value);
    localStorage.setItem("playground-stage-width", value);
  };

  handle.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) {
      return;
    }
    handle.classList.add("is-dragging");
    document.body.classList.add("is-col-resize");
    handle.setPointerCapture(event.pointerId);
    apply(event.clientX);
  });
  handle.addEventListener("pointermove", (event) => {
    if (!handle.hasPointerCapture(event.pointerId)) {
      return;
    }
    apply(event.clientX);
  });
  const stop = (event) => {
    handle.classList.remove("is-dragging");
    document.body.classList.remove("is-col-resize");
    if (handle.hasPointerCapture(event.pointerId)) {
      handle.releasePointerCapture(event.pointerId);
    }
  };
  handle.addEventListener("pointerup", stop);
  handle.addEventListener("pointercancel", stop);
  handle.addEventListener("keydown", (event) => {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") {
      return;
    }
    event.preventDefault();
    const stageWidth = mixer.querySelector(".stage").getBoundingClientRect().width;
    const delta = event.key === "ArrowRight" ? 32 : -32;
    apply(mixer.getBoundingClientRect().left + stageWidth + delta);
  });
}
