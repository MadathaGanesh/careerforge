package com.careerforge.career;

import com.careerforge.core.ApiError;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Service
public class SalaryMarketResearch {
    private static final Map<String, String> SOURCES = Map.of(
            "ambitionbox.com", "AmbitionBox", "glassdoor.co.in", "Glassdoor",
            "glassdoor.com", "Glassdoor", "naukri.com", "Naukri");

    public enum Currency {
        INR("in"), USD("us"), GBP("uk"), EUR("de"), CAD("ca"), AUD("au");

        private final String country;
        Currency(String country) { this.country = country; }
    }

    public enum Status {
        AVAILABLE, INSUFFICIENT_EVIDENCE, INCONSISTENT_EVIDENCE, UNCONFIGURED,
        SEARCH_ACCESS_REJECTED, SEARCH_LIMIT_REACHED, SEARCH_UNAVAILABLE
    }

    public record Criteria(@NotBlank @Size(max = 100) String title,
                           @NotBlank @Size(max = 100) String company,
                           @NotBlank @Size(max = 100) String location,
                           @NotNull @Min(0) @Max(60) Integer experienceFrom,
                           @NotNull @Min(0) @Max(60) Integer experienceTo,
                           @NotNull Currency currency) {}

    public record Evidence(String publisher, String title, String url, String excerpt, BigDecimal lowAnnual,
                           BigDecimal highAnnual, SalarySnippet.PayBasis payBasis, boolean usedForEstimate) {}

    public record Result(Status status, Criteria criteria, Instant searchedAt, int searchesCompleted, int searchesAttempted,
                         BigDecimal lowAnnual, BigDecimal highAnnual, SalarySnippet.PayBasis payBasis,
                         int sourcesUsed, List<Evidence> evidence, List<RelatedSource> relatedSources,
                         List<SearchCheck> searchChecks) {}

    public record RelatedSource(String publisher, String title, String url, String excerpt, boolean roleMatches,
                                boolean locationMatches, SalarySnippet.Experience experience, BigDecimal lowAnnual,
                                BigDecimal highAnnual, SalarySnippet.PayBasis payBasis) {}

    public record SearchCheck(String source, SalarySearchClient.SearchStatus status) {}
    private record SearchScope(String source, String query) {}

    private final SalarySearchClient search;

    public SalaryMarketResearch(SalarySearchClient search) {
        this.search = search;
    }

    public boolean configured() {
        return search.configured();
    }

    public Result estimate(Criteria criteria) {
        if (criteria.experienceFrom() > criteria.experienceTo()) {
            throw ApiError.invalid("The end of the experience range must be at least its start.");
        }
        Criteria clean = new Criteria(searchTerm(criteria.title()), searchTerm(criteria.company()),
                searchTerm(criteria.location()), criteria.experienceFrom(), criteria.experienceTo(), criteria.currency());
        if (!configured()) return result(Status.UNCONFIGURED, clean, List.of(), List.of(), List.of(), List.of());

        List<String> aliases = roleAliases(clean.title());
        List<SearchScope> scopes = searchPlan(clean, aliases);
        List<CompletableFuture<SalarySearchClient.Page>> calls = scopes.stream()
                .map(scope -> search.fetch(scope.query(), clean.currency().country)).toList();
        List<SalarySearchClient.Page> pages = calls.stream().map(CompletableFuture::join).toList();
        List<SearchCheck> checks = new ArrayList<>();
        for (int i = 0; i < scopes.size(); i++) checks.add(new SearchCheck(scopes.get(i).source(), pages.get(i).status()));
        if (pages.stream().noneMatch(page -> page.status() == SalarySearchClient.SearchStatus.COMPLETE)) {
            return result(failureStatus(pages), clean, checks, List.of(), List.of(), List.of());
        }

        var matches = new LinkedHashMap<String, Evidence>();
        var related = new LinkedHashMap<String, RelatedSource>();
        for (var page : pages) {
            for (var snippet : page.snippets()) {
                RelatedSource source = source(clean, aliases, snippet);
                if (source == null) continue;
                Evidence evidence = exactEvidence(source);
                if (evidence != null) {
                    matches.putIfAbsent(evidence.publisher() + ":" + evidence.payBasis(), evidence);
                } else {
                    related.putIfAbsent(source.url(), source);
                }
            }
        }
        List<Evidence> all = List.copyOf(matches.values());
        var matchedUrls = new java.util.HashSet<>(all.stream().map(Evidence::url).toList());
        List<RelatedSource> benchmarks = related.values().stream()
                .filter(source -> !matchedUrls.contains(source.url()))
                .sorted(Comparator.comparing((RelatedSource source) -> source.lowAnnual() == null)
                        .thenComparing(source -> !source.locationMatches())
                        .thenComparing(source -> !source.roleMatches()))
                .limit(8).toList();
        var grouped = new EnumMap<SalarySnippet.PayBasis, List<Evidence>>(SalarySnippet.PayBasis.class);
        for (Evidence entry : all) grouped.computeIfAbsent(entry.payBasis(), ignored -> new ArrayList<>()).add(entry);
        List<List<Evidence>> comparable = grouped.entrySet().stream()
                .filter(group -> group.getKey() != SalarySnippet.PayBasis.UNSPECIFIED)
                .map(Map.Entry::getValue).filter(group -> group.size() >= 2)
                .sorted(Comparator.<List<Evidence>>comparingInt(List::size).reversed()).toList();
        var usable = comparable.stream().filter(SalaryMarketResearch::agree).findFirst();
        if (usable.isPresent()) return result(Status.AVAILABLE, clean, checks, all, usable.get(), benchmarks);
        return result(comparable.isEmpty() ? Status.INSUFFICIENT_EVIDENCE : Status.INCONSISTENT_EVIDENCE,
                clean, checks, all, List.of(), benchmarks);
    }

    private static List<SearchScope> searchPlan(Criteria criteria, List<String> aliases) {
        var titles = new ArrayList<String>();
        titles.add(quoted(criteria.title()));
        aliases.stream().map(SalaryMarketResearch::quoted).forEach(titles::add);
        String title = titles.size() == 1 ? titles.getFirst() : "(" + String.join(" OR ", titles) + ")";
        String employerAndRole = quoted(criteria.company()) + " " + title + " salary";
        String local = employerAndRole + " " + quoted(criteria.location());
        return List.of(new SearchScope("Google web", local),
                new SearchScope("AmbitionBox", employerAndRole + " site:ambitionbox.com"),
                new SearchScope("Glassdoor", local + " (site:glassdoor.co.in OR site:glassdoor.com)"),
                new SearchScope("Naukri", employerAndRole + " site:naukri.com"));
    }

    private static String quoted(String term) {
        return '"' + term + '"';
    }

    private static List<String> roleAliases(String title) {
        String normalized = words(title);
        if (includes(normalized, "backend") || includes(normalized, "back end")) {
            return aliases(normalized, "Backend Engineer", "Backend Developer", "Software Engineer");
        }
        if (includes(normalized, "frontend") || includes(normalized, "front end")) {
            return aliases(normalized, "Frontend Engineer", "Frontend Developer", "Software Engineer");
        }
        if (includes(normalized, "full stack") || includes(normalized, "fullstack")) {
            return aliases(normalized, "Full Stack Engineer", "Full Stack Developer", "Software Engineer");
        }
        if (normalized.equals("software engineer") || normalized.equals("software developer")) {
            return aliases(normalized, "Software Engineer", "Software Developer");
        }
        return List.of();
    }

    private static List<String> aliases(String normalized, String... titles) {
        return java.util.Arrays.stream(titles).filter(title -> !words(title).equals(normalized)).toList();
    }

    private static Status failureStatus(List<SalarySearchClient.Page> pages) {
        if (pages.stream().anyMatch(page -> page.status() == SalarySearchClient.SearchStatus.ACCESS_REJECTED)) {
            return Status.SEARCH_ACCESS_REJECTED;
        }
        if (pages.stream().anyMatch(page -> page.status() == SalarySearchClient.SearchStatus.LIMIT_REACHED)) {
            return Status.SEARCH_LIMIT_REACHED;
        }
        return Status.SEARCH_UNAVAILABLE;
    }

    private static boolean agree(List<Evidence> sources) {
        BigDecimal highestLow = sources.stream().map(Evidence::lowAnnual).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal lowestHigh = sources.stream().map(Evidence::highAnnual).min(Comparator.naturalOrder()).orElseThrow();
        return highestLow.compareTo(lowestHigh.multiply(new BigDecimal("1.25"))) <= 0;
    }

    private static Result result(Status status, Criteria criteria, List<SearchCheck> checks, List<Evidence> all,
                                 List<Evidence> used, List<RelatedSource> related) {
        int done = (int) checks.stream().filter(check -> check.status() == SalarySearchClient.SearchStatus.COMPLETE).count();
        List<Evidence> cited = all.stream().map(entry -> new Evidence(entry.publisher(), entry.title(), entry.url(), entry.excerpt(),
                entry.lowAnnual(), entry.highAnnual(), entry.payBasis(), used.contains(entry))).toList();
        return new Result(status, criteria, checks.isEmpty() ? null : Instant.now(), done, checks.size(),
                used.isEmpty() ? null : median(used.stream().map(Evidence::lowAnnual).toList()),
                used.isEmpty() ? null : median(used.stream().map(Evidence::highAnnual).toList()),
                used.isEmpty() ? null : used.getFirst().payBasis(), used.size(), cited, related, List.copyOf(checks));
    }

    private static BigDecimal median(List<BigDecimal> samples) {
        var sorted = samples.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                : sorted.get(middle);
    }

    private static Evidence exactEvidence(RelatedSource source) {
        if (!source.roleMatches() || !source.locationMatches() || source.experience() != SalarySnippet.Experience.WITHIN_RANGE
                || source.lowAnnual() == null) return null;
        return new Evidence(source.publisher(), source.title(), source.url(), source.excerpt(),
                source.lowAnnual(), source.highAnnual(), source.payBasis(), false);
    }

    static RelatedSource source(Criteria criteria, List<String> aliases, SalarySearchClient.Snippet result) {
        if (result.link().length() > 2048) return null;
        String publisher = publisher(result.link());
        if (publisher == null) return null;
        String path = cleanText(java.util.Objects.toString(URI.create(result.link()).getPath(), ""), 600);
        String title = cleanText(result.title(), 240);
        String snippet = cleanText(result.description(), 600);
        var parts = new LinkedHashSet<String>();
        if (!snippet.isBlank()) parts.add(snippet);
        result.details().stream().map(detail -> cleanText(detail, 160)).filter(detail -> !detail.isBlank()).forEach(parts::add);
        String excerpt = String.join(" · ", parts);
        String summary = title + " " + excerpt + " " + path;
        if (!includes(summary, criteria.company()) || !SalarySnippet.salaryPage(summary)) return null;
        boolean exactRole = includes(summary, criteria.title());
        if (!exactRole && aliases.stream().noneMatch(alias -> includes(summary, alias))) return null;
        boolean location = includes(summary, criteria.location());

        // A number attached to a role-specific title or URL is easier to attribute than a number on a generic salary list.
        String page = title + " " + path;
        boolean roleSpecific = includes(page, criteria.title()) || aliases.stream().anyMatch(alias -> includes(page, alias));
        if (!title.isBlank()) parts.add(title);
        var amounts = new LinkedHashSet<SalarySnippet.Pay>();
        for (String part : parts) {
            if (!roleSpecific && !includes(part, criteria.title())) continue;
            SalarySnippet.Pay amount = SalarySnippet.read(part, criteria.currency());
            if (amount != null) amounts.add(amount);
        }
        SalarySnippet.Pay pay = amounts.size() == 1 ? amounts.getFirst() : null;
        return new RelatedSource(publisher, title.isBlank() ? publisher + " salary page" : title, result.link(), excerpt,
                exactRole, location, SalarySnippet.experience(title + " " + excerpt, criteria),
                pay == null ? null : pay.low(), pay == null ? null : pay.high(), pay == null ? null : pay.basis());
    }

    private static String cleanText(String text, int max) {
        String cleaned = text.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String searchTerm(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}\"“”]", " ").replaceAll("\\s+", " ").strip();
        if (words(clean).isBlank()) throw ApiError.invalid("Enter a job title, company and location.");
        return clean;
    }

    private static boolean includes(String text, String term) {
        String tokens = " " + words(text) + " ";
        for (String word : words(term).split(" ")) {
            if (!tokens.contains(" " + word + " ")) return false;
        }
        return true;
    }

    private static String words(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#]+", " ").strip();
    }

    private static String publisher(String url) {
        try {
            URI link = URI.create(url);
            if (link.getHost() == null || link.getUserInfo() != null
                    || !List.of("https", "http").contains(link.getScheme().toLowerCase(Locale.ROOT))) return null;
            String host = link.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (!Pattern.matches("(?:[a-z0-9-]+\\.)+[a-z]{2,63}", host)
                    || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")) return null;
            if (host.matches("(?:.*\\.)?google\\.(?:com|co\\.[a-z]{2}|[a-z]{2})")) return null;
            for (var source : SOURCES.entrySet()) {
                if (host.equals(source.getKey()) || host.endsWith("." + source.getKey())) return source.getValue();
            }
            String[] parts = host.split("\\.");
            int depth = host.matches(".*\\.(?:ac|co|com|gov|net|org)\\.[a-z]{2}$") ? 3 : 2;
            if (parts.length < depth) return null;
            return String.join(".", java.util.Arrays.copyOfRange(parts, parts.length - depth, parts.length));
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
    }
}
