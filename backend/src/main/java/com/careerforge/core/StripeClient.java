package com.careerforge.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StripeClient {
    private final Db db;
    private final String secret;
    private final String price;
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @org.springframework.beans.factory.annotation.Autowired
    public StripeClient(Db db, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.price-pro:}") String price) {
        this(db, secret, price, URI.create("https://api.stripe.com/v1/"));
    }

    StripeClient(Db db, String secret, String price, URI base) {
        this.db = db;
        this.base = base;
        this.secret = secret;
        this.price = price;
    }

    public boolean configured() {
        return !secret.isBlank() && !price.isBlank();
    }

    public String price() { return price; }

    public JsonNode get(String resource, String id, String prefix) {
        if (!id.startsWith(prefix) || !id.matches("[A-Za-z0-9_]{1,200}")) throw ApiError.invalid("Invalid billing reference");
        return request(resource + "/" + id, null, null);
    }

    public JsonNode post(String endpoint, Map<String, String> parameters, String key) {
        String form = parameters.entrySet().stream()
                .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue())).collect(Collectors.joining("&"));
        return request(endpoint, form, key);
    }

    private JsonNode request(String endpoint, String form, String key) {
        if (!configured()) throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_UNCONFIGURED", "Payment provider not configured");
        try {
            var request = HttpRequest.newBuilder(base.resolve(endpoint))
                    .header("Authorization", "Bearer " + secret).timeout(Duration.ofSeconds(12));
            if (key != null) request.header("Idempotency-Key", key);
            if (form == null) request.GET();
            else request.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) return db.json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The response may be unavailable after Stripe accepted a write. Reuse its request key.
        }
        throw new ApiError(HttpStatus.BAD_GATEWAY, "BILLING_UNAVAILABLE", "Payment service unavailable. Try again.");
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
