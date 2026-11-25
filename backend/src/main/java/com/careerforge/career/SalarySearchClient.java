package com.careerforge.career;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SalarySearchClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String endpoint;
    private final String key;
    private final Duration deadline;

    @Autowired
    public SalarySearchClient(ObjectMapper json, @Value("${app.salary-search-url}") String endpoint,
                              @Value("${app.salary-search-key:}") String key) {
        this(json, endpoint, key, Duration.ofSeconds(20));
    }

    SalarySearchClient(ObjectMapper json, String endpoint, String key, Duration deadline) {
        this.json = json;
        this.endpoint = endpoint;
        this.key = key.strip();
        this.deadline = deadline;
    }

    public boolean configured() {
        return !key.isEmpty();
    }

    record Snippet(String title, String link, String description, List<String> details) {
        Snippet(String title, String link, String description) {
            this(title, link, description, List.of());
        }
    }
    enum SearchStatus { COMPLETE, ACCESS_REJECTED, LIMIT_REACHED, TIMED_OUT, UNAVAILABLE }

    record Page(SearchStatus status, List<Snippet> snippets) {
        static Page failed(SearchStatus status) {
            return new Page(status, List.of());
        }
    }

    CompletableFuture<Page> fetch(String query, String country) {
        try {
            URI url = URI.create(endpoint + "?engine=google&hl=en&gl=" + country
                    + "&q=" + encode(query) + "&api_key=" + encode(key));
            HttpRequest request = HttpRequest.newBuilder(url).timeout(deadline)
                    .header("Accept", "application/json").GET().build();
            var exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            return exchange.copy().orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .thenApply(this::parse)
                    .exceptionally(failure -> {
                        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                            exchange.cancel(true);
                            return Page.failed(SearchStatus.TIMED_OUT);
                        }
                        return Page.failed(SearchStatus.UNAVAILABLE);
                    });
        } catch (IllegalArgumentException invalidConfiguration) {
            return CompletableFuture.completedFuture(Page.failed(SearchStatus.UNAVAILABLE));
        }
    }

    private Page parse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return Page.failed(switch (response.statusCode()) {
                case 401, 403 -> SearchStatus.ACCESS_REJECTED;
                case 429 -> SearchStatus.LIMIT_REACHED;
                default -> SearchStatus.UNAVAILABLE;
            });
        }
        if (response.body().length() > 1_000_000) return Page.failed(SearchStatus.UNAVAILABLE);
        try {
            var body = json.readTree(response.body());
            if (body == null || !body.isObject()
                    || !"Success".equalsIgnoreCase(body.path("search_metadata").path("status").asText())) {
                return Page.failed(SearchStatus.UNAVAILABLE);
            }
            boolean empty = "Fully empty".equalsIgnoreCase(body.path("search_information").path("organic_results_state").asText());
            if (body.has("error") && !empty) return Page.failed(SearchStatus.UNAVAILABLE);
            List<Snippet> snippets = new ArrayList<>();
            for (var item : body.path("organic_results")) {
                if (snippets.size() == 10) break;
                snippets.add(new Snippet(item.path("title").asText(), item.path("link").asText(), item.path("snippet").asText(),
                        details(item)));
            }
            return new Page(SearchStatus.COMPLETE, List.copyOf(snippets));
        } catch (java.io.IOException invalidJson) {
            return Page.failed(SearchStatus.UNAVAILABLE);
        }
    }

    private static List<String> details(JsonNode item) {
        var rich = item.path("rich_snippet");
        List<String> result = new ArrayList<>();
        for (var extensions : List.of(item.path("extensions"), rich.path("top").path("extensions"), rich.path("bottom").path("extensions"))) {
            if (!extensions.isArray()) continue;
            for (var extension : extensions) {
                if (result.size() == 6) return List.copyOf(result);
                if (extension.isTextual() && !extension.asText().isBlank()) result.add(extension.asText());
            }
        }
        return List.copyOf(result);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
