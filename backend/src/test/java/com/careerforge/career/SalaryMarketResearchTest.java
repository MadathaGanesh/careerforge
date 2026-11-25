package com.careerforge.career;

import com.careerforge.core.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SalaryMarketResearchTest {
    private static final String TITLE = "Java Backend Developer at Example Co in Hyderabad";
    private static final SalaryMarketResearch.Criteria INDIA = criteria(SalaryMarketResearch.Currency.INR);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void searchesFourScopesAndUsesOneMatchingRangePerPublisher() throws Exception {
        var entries = List.of(
                source("www.ambitionbox.com", "Annual CTC ₹8–10 LPA for 3–4 years of experience."),
                source("www.glassdoor.co.in", "Annual total pay is ₹9L to ₹11L/yr for 4 years experience."),
                source("glassdoor.com", "Annual total pay is ₹15L to ₹16L/yr for 4 years experience."),
                source("www.naukri.com", "Annual base pay of ₹50L–₹60L/yr for 3–4 years experience."),
                source("www.unrelated.com", "Annual total compensation ₹2L–₹3L/yr for 7–9 years experience."));
        var queryLog = new CopyOnWriteArrayList<String>();
        HttpServer server = fixture(entries, queryLog);
        try {
            var result = research(server, "test-key").estimate(INDIA);
            assertEquals(SalaryMarketResearch.Status.AVAILABLE, result.status());
            assertEquals(4, result.searchesCompleted());
            assertEquals(2, result.sourcesUsed());
            assertEquals(0, result.lowAnnual().compareTo(new BigDecimal("850000")));
            assertEquals(0, result.highAnnual().compareTo(new BigDecimal("1050000")));
            assertEquals(3, result.evidence().size());
            assertTrue(queryLog.stream().allMatch(query -> query.contains("engine=google")
                    && query.contains("api_key=test-key") && query.contains("\"Example Co\"")
                    && query.contains("\"Software Engineer\"")));
            assertTrue(queryLog.stream().anyMatch(query -> query.contains("\"Hyderabad\"")));
            assertTrue(queryLog.stream().anyMatch(query -> !query.contains("\"Hyderabad\"")));
            for (String host : List.of("ambitionbox.com", "glassdoor.co.in", "naukri.com")) {
                assertTrue(queryLog.stream().anyMatch(query -> query.contains("site:" + host)));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesPublicPayFormatsAndLabelsLimitsInTheSource() {
        String yearly = "Annual base pay ₹8,00,000 – ₹10,00,000 for 3 to 4 years experience.";
        assertEquals(SalarySnippet.Experience.WITHIN_RANGE, source(INDIA, TITLE, yearly).experience());
        assertFalse(source(INDIA, TITLE.replace("Hyderabad", "Pune"), yearly).locationMatches());
        assertEquals(SalarySnippet.Experience.OUTSIDE_RANGE, source(INDIA, TITLE, yearly.replace("3 to 4", "5–7")).experience());
        assertEquals(SalarySnippet.Experience.BROADER, source(INDIA, TITLE, yearly.replace("3 to 4", "2–5")).experience());
        assertNull(source(INDIA, TITLE, "Monthly salary ₹80K–₹100K/month for 4 years experience.").lowAnnual());
        assertNull(source(INDIA, TITLE, "Annual base salary $80K–$100K/yr for 4 years experience.").lowAnnual());
        assertNull(source(INDIA, TITLE, "Annual salary ₹8L–$100K/yr for 4 years experience.").lowAnnual());
        assertEquals(SalarySnippet.Experience.UNSTATED, source(INDIA, TITLE, yearly.replace("3 to 4 years experience", "4 reviews")).experience());
        assertEquals(SalarySnippet.Experience.WITHIN_RANGE,
                source(INDIA, TITLE, yearly.replace("3 to 4 years experience", "experience: 3 to 4 years")).experience());
        var foreign = source(criteria(SalaryMarketResearch.Currency.USD), TITLE,
                "Annual base salary US$80K–US$100K/yr for 4 years experience.");
        assertEquals(0, foreign.highAnnual().compareTo(new BigDecimal("100000")));
    }

    @Test
    void reportsInsufficientAndConflictingEvidenceWithoutReturningAmounts() throws Exception {
        var onlyOne = fixture(List.of(source("naukri.com", "Total salary ₹8–10 LPA for 4 years experience.")), new CopyOnWriteArrayList<>());
        var conflict = fixture(List.of(source("naukri.com", "Total salary ₹8–10 LPA for 4 years experience."),
                source("ambitionbox.com", "Total salary ₹24–30 LPA for 4 years experience.")), new CopyOnWriteArrayList<>());
        try {
            assertEquals(SalaryMarketResearch.Status.INSUFFICIENT_EVIDENCE,
                    research(onlyOne, "fixture").estimate(INDIA).status());
            var result = research(conflict, "fixture").estimate(INDIA);
            assertEquals(SalaryMarketResearch.Status.INCONSISTENT_EVIDENCE, result.status());
            assertNull(result.lowAnnual());
            assertEquals(2, result.evidence().size());
        } finally {
            onlyOne.stop(0);
            conflict.stop(0);
        }
    }

    @Test
    void missingKeyMakesNoProviderCalls() throws Exception {
        var queryLog = new CopyOnWriteArrayList<String>();
        HttpServer server = fixture(List.of(), queryLog);
        try {
            var unconfigured = research(server, "  ").estimate(INDIA);
            assertEquals(SalaryMarketResearch.Status.UNCONFIGURED, unconfigured.status());
            assertEquals(0, unconfigured.searchesAttempted());
            assertTrue(queryLog.isEmpty());
            assertThrows(ApiError.class, () -> research(server, "").estimate(
                    new SalaryMarketResearch.Criteria("Developer", "Example Co", "Hyderabad", 8, 3, SalaryMarketResearch.Currency.INR)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void distinguishesProviderAccessQuotaAndServiceFailures() throws Exception {
        var cases = Map.of(401, SalaryMarketResearch.Status.SEARCH_ACCESS_REJECTED,
                403, SalaryMarketResearch.Status.SEARCH_ACCESS_REJECTED,
                429, SalaryMarketResearch.Status.SEARCH_LIMIT_REACHED,
                503, SalaryMarketResearch.Status.SEARCH_UNAVAILABLE);
        for (var entry : cases.entrySet()) {
            HttpServer server = fixture(List.of(), new CopyOnWriteArrayList<>(), entry.getKey());
            try {
                var result = research(server, "fixture").estimate(INDIA);
                assertEquals(entry.getValue(), result.status());
                assertEquals(0, result.searchesCompleted());
                assertEquals(4, result.searchesAttempted());
                assertNull(result.lowAnnual());
                assertTrue(result.evidence().isEmpty());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void completedEmptySearchShowsInsufficientEvidence() throws Exception {
        HttpServer server = fixture(List.of(), new CopyOnWriteArrayList<>());
        try {
            var result = research(server, "fixture").estimate(INDIA);
            assertEquals(SalaryMarketResearch.Status.INSUFFICIENT_EVIDENCE, result.status());
            assertEquals(4, result.searchesCompleted());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsCityAndCountryBenchmarksWhenTwoSearchesFail() throws Exception {
        var criteria = new SalaryMarketResearch.Criteria("Consultant", "Genpact", "Hyderabad",
                3, 5, SalaryMarketResearch.Currency.INR);
        var entries = List.of(
                Map.of("title", "Genpact salaries in Hyderabad", "link", "https://in.indeed.com/cmp/Genpact/locations/TS/Hyderabad",
                        "snippet", "Consultant₹ 8,58,343 per year 11 salaries reported."),
                Map.of("title", "Genpact Consultant Salary in India", "link", "https://www.weekday.works/salary/genpact/consultant",
                        "snippet", "The annual fixed pay ranges from ₹7.7 to ₹13 LPA for 4–6 years of experience."),
                Map.of("title", "About Genpact", "link", "https://www.genpact.com/about", "snippet", "Company overview"));
        HttpServer server = fixture(entries, new CopyOnWriteArrayList<>(),
                query -> query.contains("site:ambitionbox.com") ? 503 : query.contains("site:naukri.com") ? 429 : 200);
        try {
            var result = research(server, "fixture").estimate(criteria);
            assertEquals(SalaryMarketResearch.Status.INSUFFICIENT_EVIDENCE, result.status());
            assertEquals(2, result.searchesCompleted());
            assertEquals(4, result.searchesAttempted());
            assertEquals(List.of(SalarySearchClient.SearchStatus.COMPLETE, SalarySearchClient.SearchStatus.UNAVAILABLE,
                    SalarySearchClient.SearchStatus.COMPLETE, SalarySearchClient.SearchStatus.LIMIT_REACHED),
                    result.searchChecks().stream().map(SalaryMarketResearch.SearchCheck::status).toList());
            assertNull(result.lowAnnual());
            assertTrue(result.evidence().isEmpty());
            assertEquals(2, result.relatedSources().size());
            assertEquals(0, result.relatedSources().getFirst().lowAnnual().compareTo(new BigDecimal("858343")));
            assertTrue(result.relatedSources().getFirst().locationMatches());
            assertFalse(result.relatedSources().get(1).locationMatches());
            assertEquals(SalarySnippet.Experience.BROADER, result.relatedSources().get(1).experience());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void broaderTitleAndMissingNumbersStillReturnUsefulLinks() throws Exception {
        var entries = List.of(
                Map.of("title", "Software Engineer salary at Example Co in Hyderabad",
                        "link", "https://levels.fyi/companies/example-co/salaries/software-engineer/hyderabad",
                        "snippet", "Annual total pay ₹19–22 LPA for 3–4 years experience."),
                Map.of("title", "Java Backend Developer salary at Example Co in Hyderabad",
                        "link", "https://salary.example.com/roles/java-backend-developer/hyderabad",
                        "snippet", "Open the source for the full pay breakdown."),
                Map.of("title", "Java Backend Developer salary at Other Co in Hyderabad",
                        "link", "https://other.example.com/salary/java-backend-developer", "snippet", "Total pay ₹9 LPA."));
        HttpServer server = fixture(entries, new CopyOnWriteArrayList<>());
        try {
            var result = research(server, "fixture").estimate(INDIA);
            assertNull(result.lowAnnual());
            assertEquals(2, result.relatedSources().size());
            assertFalse(result.relatedSources().getFirst().roleMatches());
            assertNotNull(result.relatedSources().getFirst().lowAnnual());
            assertNull(result.relatedSources().get(1).lowAnnual());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsRichSnippetsAndKeepsBasePayAndUnknownPaySeparate() throws Exception {
        var rich = Map.of("title", TITLE + " Salary", "link", "https://ambitionbox.com/salary/example",
                "rich_snippet", Map.of("top", Map.of("extensions",
                        List.of("Annual base pay ₹8–10 LPA", "Experience: 3–4 years"))));
        var entries = List.of(rich,
                source("glassdoor.com", "Annual base pay ₹9–11 LPA for 3–4 years experience."),
                source("naukri.com", "Salary ₹7–8 LPA for 3–4 years experience."));
        HttpServer server = fixture(entries, new CopyOnWriteArrayList<>());
        try {
            var result = research(server, "fixture").estimate(INDIA);
            assertEquals(SalaryMarketResearch.Status.AVAILABLE, result.status());
            assertEquals(SalarySnippet.PayBasis.BASE_PAY, result.payBasis());
            assertEquals(2, result.sourcesUsed());
            assertEquals(0, result.lowAnnual().compareTo(new BigDecimal("850000")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsAProviderTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search.json", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new SalarySearchClient(json, "http://127.0.0.1:" + server.getAddress().getPort() + "/search.json",
                    "test-key", Duration.ofMillis(100));
            assertEquals(SalarySearchClient.SearchStatus.TIMED_OUT, client.fetch("salary sources", "in").join().status());
        } finally {
            server.stop(0);
        }
    }

    private static SalaryMarketResearch.Criteria criteria(SalaryMarketResearch.Currency currency) {
        return new SalaryMarketResearch.Criteria("Java Backend Developer", "Example Co", "Hyderabad", 3, 4, currency);
    }

    private static SalaryMarketResearch.RelatedSource source(SalaryMarketResearch.Criteria criteria, String title, String text) {
        return SalaryMarketResearch.source(criteria, List.of(),
                new SalarySearchClient.Snippet(title, "https://www.glassdoor.com/salary/example", text));
    }

    private static Map<String, String> source(String host, String snippet) {
        return Map.of("title", TITLE, "link", "https://" + host + "/salary/example", "snippet", snippet);
    }

    private SalaryMarketResearch research(HttpServer server, String key) {
        return new SalaryMarketResearch(new SalarySearchClient(json,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/search.json", key));
    }

    private HttpServer fixture(List<? extends Map<String, ?>> entries, List<String> queryLog) throws Exception {
        return fixture(entries, queryLog, 200);
    }

    private HttpServer fixture(List<? extends Map<String, ?>> entries, List<String> queryLog, int status) throws Exception {
        return fixture(entries, queryLog, query -> status);
    }

    private HttpServer fixture(List<? extends Map<String, ?>> entries, List<String> queryLog, Function<String, Integer> status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var metadata = Map.of("status", "Success");
        byte[] output = json.writeValueAsBytes(entries.isEmpty()
                ? Map.of("search_metadata", metadata, "search_information", Map.of("organic_results_state", "Fully empty"),
                        "error", "Google has no results for this query.")
                : Map.of("search_metadata", metadata, "organic_results", entries));
        server.createContext("/search.json", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            queryLog.add(query);
            exchange.sendResponseHeaders(status.apply(query), output.length);
            try (var body = exchange.getResponseBody()) {
                body.write(output);
            }
        });
        server.start();
        return server;
    }
}
