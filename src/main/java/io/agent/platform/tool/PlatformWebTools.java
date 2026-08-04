/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lightweight web tools for validating agent research/tool-calling wiring. */
public class PlatformWebTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_FETCH_CHARS = 6000;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Tool(
            name = "web_search",
            description =
                    "Search the public web for current facts. Returns a compact list of titles,"
                            + " URLs, and snippets. Use before answering questions that require"
                            + " recent or external information.",
            readOnly = true)
    public String webSearch(
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(
                            name = "max_results",
                            description = "Maximum number of results, default 5",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "Error: query cannot be blank.";
        }
        int limit =
                maxResults == null ? DEFAULT_MAX_RESULTS : Math.max(1, Math.min(10, maxResults));
        try {
            String url =
                    "https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "application/json")
                            .header("User-Agent", "AgentPlatform/0.1")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Error: search request failed with HTTP " + response.statusCode();
            }
            Map<String, Object> raw =
                    JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            List<SearchResult> results = new ArrayList<>();
            addDirectAnswer(raw, results);
            collectRelated(raw.get("RelatedTopics"), results, limit);
            if (results.isEmpty()) {
                return "No web search results found for query: " + query;
            }
            StringBuilder out = new StringBuilder();
            out.append("Web search results for: ").append(query).append("\n");
            for (int i = 0; i < Math.min(limit, results.size()); i++) {
                SearchResult result = results.get(i);
                out.append(i + 1)
                        .append(". ")
                        .append(result.title())
                        .append("\n   URL: ")
                        .append(result.url())
                        .append("\n   Snippet: ")
                        .append(result.snippet())
                        .append("\n");
            }
            return out.toString();
        } catch (Exception e) {
            return "Error: web_search failed: " + e.getMessage();
        }
    }

    @Tool(
            name = "web_fetch",
            description =
                    "Fetch a public HTTP/HTTPS page and return a plain-text excerpt. Use after"
                            + " web_search when you need to inspect one source URL.",
            readOnly = true)
    public String webFetch(
            @ToolParam(name = "url", description = "HTTP or HTTPS URL to fetch") String url,
            @ToolParam(
                            name = "max_chars",
                            description = "Maximum returned characters, default 6000",
                            required = false)
                    Integer maxChars) {
        if (url == null || url.isBlank()) {
            return "Error: url cannot be blank.";
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (Exception e) {
            return "Error: invalid URL.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "Error: only http/https URLs are allowed.";
        }
        int limit = maxChars == null ? MAX_FETCH_CHARS : Math.max(500, Math.min(12000, maxChars));
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "text/html,text/plain,application/json")
                            .header("User-Agent", "AgentPlatform/0.1")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Error: fetch request failed with HTTP " + response.statusCode();
            }
            String text = normalizeText(response.body());
            if (text.length() > limit) {
                text = text.substring(0, limit) + "\n...[truncated]";
            }
            return "Fetched URL: " + uri + "\n\n" + text;
        } catch (Exception e) {
            return "Error: web_fetch failed: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRelated(Object node, List<SearchResult> out, int limit) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (out.size() >= limit) {
                    return;
                }
                collectRelated(item, out, limit);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Object nested = map.get("Topics");
        if (nested != null) {
            collectRelated(nested, out, limit);
            return;
        }
        String text = string(map.get("Text"));
        String firstUrl = string(map.get("FirstURL"));
        if (!text.isBlank() && !firstUrl.isBlank()) {
            String title = text;
            int dash = title.indexOf(" - ");
            if (dash > 0) {
                title = title.substring(0, dash);
            }
            out.add(new SearchResult(title, firstUrl, text));
        }
    }

    private static void addDirectAnswer(Map<String, Object> raw, List<SearchResult> out) {
        String answer = string(raw.get("AbstractText"));
        String url = string(raw.get("AbstractURL"));
        String heading = string(raw.get("Heading"));
        if (!answer.isBlank() && !url.isBlank()) {
            out.add(new SearchResult(heading.isBlank() ? "Direct answer" : heading, url, answer));
        }
    }

    private static String normalizeText(String value) {
        return value.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private record SearchResult(String title, String url, String snippet) {}
}
