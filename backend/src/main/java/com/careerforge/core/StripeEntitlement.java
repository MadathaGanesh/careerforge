package com.careerforge.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

record StripeEntitlement(String status, String plan, Instant periodEnd) {
    static StripeEntitlement from(JsonNode subscription, String expectedPrice) {
        String state = subscription.path("status").asText("unknown").toLowerCase(Locale.ROOT);
        long current = subscription.path("current_period_end").asLong(0);
        boolean matchingPrice = false;
        for (var item : subscription.path("items").path("data")) {
            if (expectedPrice.equals(item.path("price").path("id").asText())) {
                matchingPrice = true;
                current = Math.max(current, item.path("current_period_end").asLong(0));
            }
        }
        boolean entitled = matchingPrice && Set.of("active", "trialing").contains(state);
        return new StripeEntitlement(state.toUpperCase(Locale.ROOT), entitled ? "PRO" : "FREE",
                current > 0 ? Instant.ofEpochSecond(current) : null);
    }
}
