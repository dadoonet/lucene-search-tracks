package fr.pilato.test.lucene.playground;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.apache.lucene.util.Version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fr.pilato.test.lucene.playground.PlaygroundModels.AnalyzeRequest;
import static fr.pilato.test.lucene.playground.PlaygroundModels.FacetsRequest;
import static fr.pilato.test.lucene.playground.PlaygroundModels.MapRequest;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SearchRequest;
import static fr.pilato.test.lucene.playground.PlaygroundModels.SuggestRequest;

public final class PlaygroundApp {

    public static final int PORT = 7171;

    private PlaygroundApp() {}

    static void main() throws Exception {
        PlaygroundService service = PlaygroundService.boot();
        Javalin app = create(service).start(PORT);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            try {
                service.close();
            } catch (Exception _) {
                // shutdown
            }
        }));
        System.out.println("Lucene playground → http://localhost:" + PORT);
    }

    public static Javalin create(PlaygroundService service) {
        return Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.routes.exception(Exception.class, (e, ctx) ->
                    ctx.status(500).json(Map.of("error", String.valueOf(e.getMessage()))));
            config.routes.get("/", ctx -> ctx.html(indexHtml()));
            config.routes.get("/api/meta", ctx -> ctx.json(service.meta()));
            config.routes.get("/api/analyze", ctx ->
                    ctx.json(service.analyze(ctx.queryParam("text"))));
            config.routes.post("/api/analyze", ctx -> {
                AnalyzeRequest body = ctx.bodyAsClass(AnalyzeRequest.class);
                ctx.json(service.analyze(body == null ? "" : body.text()));
            });
            config.routes.get("/api/map", ctx ->
                    ctx.json(service.map(ctx.queryParam("id"))));
            config.routes.post("/api/map", ctx -> {
                MapRequest body = ctx.bodyAsClass(MapRequest.class);
                ctx.json(service.map(body == null ? null : body.id()));
            });
            config.routes.get("/api/index", ctx ->
                    ctx.json(service.invertedIndex(ctx.queryParam("term"), ctx.queryParam("field"))));
            config.routes.get("/api/search", ctx -> ctx.json(service.search(fromQuery(ctx.queryParam("q"),
                    ctx.queryParam("genre"), ctx.queryParam("minusKey"), ctx.queryParam("explainDoc")))));
            config.routes.post("/api/search", ctx ->
                    ctx.json(service.search(ctx.bodyAsClass(SearchRequest.class))));
            config.routes.get("/api/suggest", ctx ->
                    ctx.json(service.suggest(ctx.queryParam("prefix"))));
            config.routes.post("/api/suggest", ctx -> {
                SuggestRequest body = ctx.bodyAsClass(SuggestRequest.class);
                ctx.json(service.suggest(body == null ? "" : body.prefix()));
            });
            config.routes.get("/api/facets", ctx ->
                    ctx.json(service.facets(ctx.queryParam("q"), ctx.queryParam("drillGenre"))));
            config.routes.post("/api/facets", ctx -> {
                FacetsRequest body = ctx.bodyAsClass(FacetsRequest.class);
                ctx.json(service.facets(
                        body == null ? "" : body.q(),
                        facetFilters(body),
                        body == null || body.mustNots() == null ? Map.of() : body.mustNots()));
            });
        });
    }

    private static SearchRequest fromQuery(String q, String genre, String minusKey, String explainDoc) {
        Map<String, List<String>> filters = genre == null || genre.isBlank()
                ? Map.of()
                : Map.of("genre", List.of(genre));
        Map<String, List<String>> mustNots = minusKey == null || minusKey.isBlank()
                ? Map.of()
                : Map.of("key", List.of(minusKey.split("\\s*,\\s*")));
        Integer doc = null;
        if (explainDoc != null && !explainDoc.isBlank()) {
            doc = Integer.parseInt(explainDoc);
        }
        return new SearchRequest(q == null ? "" : q, filters, mustNots, doc);
    }

    private static Map<String, List<String>> facetFilters(FacetsRequest body) {
        if (body == null) {
            return Map.of();
        }
        Map<String, List<String>> filters = body.filters() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(body.filters());
        if (body.drillGenre() != null && !body.drillGenre().isBlank()) {
            filters.put("genre", List.of(body.drillGenre()));
        }
        return filters;
    }

    private static String indexHtml() throws IOException {
        try (InputStream in = PlaygroundApp.class.getResourceAsStream("/public/index.html")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource /public/index.html");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{lucene.version}}", Version.LATEST.toString());
        }
    }
}
