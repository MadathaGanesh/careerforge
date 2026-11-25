package com.careerforge.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StripeClientContractTest {
    @Test
    void checkoutIdempotencyPortalAndAuthoritativeStateUseTheProviderContract() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var checkoutBody = new AtomicReference<String>();
        var checkoutKey = new AtomicReference<String>();
        var phase = new AtomicReference<>("active");
        server.createContext("/v1/", exchange -> {
            try (exchange) {
                String route = exchange.getRequestURI().getPath();
                String response = switch (route) {
                    case "/v1/checkout/sessions" -> {
                        checkoutKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        checkoutBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        yield "{\"id\":\"cs_fixture\",\"url\":\"https://checkout.stripe.com/test-fixture\"}";
                    }
                    case "/v1/billing_portal/sessions" -> "{\"url\":\"https://billing.stripe.com/test-fixture\"}";
                    case "/v1/subscriptions/sub_fixture" -> """
                            {"status":"%s","customer":"cus_fixture","items":{"data":[{"price":{"id":"price_pro"},"current_period_end":1800000000}]}}
                            """.formatted(phase.get());
                    default -> "{}";
                };
                byte[] data = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.start();
        try {
            var stripe = new StripeClient(new Db(null, new ObjectMapper()), "fixture-token", "price_pro",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"));
            assertEquals("cs_fixture", stripe.post("checkout/sessions", Map.of("line_items[0][price]", "price_pro"),
                    "stable-fixture-key").path("id").asText());
            assertEquals("stable-fixture-key", checkoutKey.get());
            assertTrue(checkoutBody.get().contains("line_items%5B0%5D%5Bprice%5D=price_pro"));
            assertTrue(stripe.post("billing_portal/sessions", Map.of("customer", "cus_fixture"), null)
                    .path("url").asText().startsWith("https://billing.stripe.com/"));
            assertEquals("PRO", StripeEntitlement.from(stripe.get("subscriptions", "sub_fixture", "sub_"), stripe.price()).plan());
            phase.set("past_due");
            assertEquals("FREE", StripeEntitlement.from(stripe.get("subscriptions", "sub_fixture", "sub_"), stripe.price()).plan());
            phase.set("canceled");
            assertEquals("CANCELED", StripeEntitlement.from(stripe.get("subscriptions", "sub_fixture", "sub_"), stripe.price()).status());
        } finally {
            server.stop(0);
        }
    }
}
