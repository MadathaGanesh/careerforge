package com.careerforge.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StripeEntitlementTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyActiveOrTrialingMatchingPriceGrantsPro() throws Exception {
        for (String status : new String[]{"active", "trialing", "past_due", "unpaid", "paused", "canceled", "incomplete"}) {
            String payload = """
                    {"status":"%s","items":{"data":[{"current_period_end":1800000000,"price":{"id":"price_PRO"}}]}}
                    """.formatted(status);
            var pro = StripeEntitlement.from(json.readTree(payload), "price_PRO");
            assertEquals(status.equals("active") || status.equals("trialing") ? "PRO" : "FREE", pro.plan());
            assertEquals(Instant.ofEpochSecond(1800000000), pro.periodEnd());
            assertEquals("FREE", StripeEntitlement.from(json.readTree(payload), "price_different").plan());
        }
    }
}
