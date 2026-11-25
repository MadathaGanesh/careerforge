package com.careerforge.api;

import com.careerforge.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final Db db;
    private final StripeSubscriptionSync billing;
    private final NotificationService notifications;
    private final String webhookSecret;

    public BillingController(Db db, StripeSubscriptionSync billing, NotificationService notifications,
                             @Value("${stripe.webhook-secret:}") String webhookSecret) {
        this.db = db;
        this.billing = billing;
        this.notifications = notifications;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout() {
        return billing.checkout(db.uid());
    }

    @PostMapping("/portal")
    public Map<String, Object> portal() {
        return billing.portal(db.uid());
    }

    @Transactional
    @PostMapping("/webhook")
    public Map<String, Object> webhook(@RequestBody String raw,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (raw.length() > 500000 || !valid(raw, signature)) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK", "Webhook signature invalid");
        }
        JsonNode event;
        try {
            event = db.json.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiError.invalid("Invalid billing event");
        }
        String id = event.path("id").asText();
        String type = event.path("type").asText();
        if (id.isBlank() || type.isBlank()) throw ApiError.invalid("Missing billing event metadata");
        if (db.jdbc.update("INSERT INTO billing_events(id,provider,payload_type) VALUES (?,'STRIPE',?) ON CONFLICT DO NOTHING",
                id, type) == 0) return Map.of("received", true, "duplicate", true);
        JsonNode object = event.path("data").path("object");
        if (Set.of("checkout.session.completed", "checkout.session.async_payment_succeeded", "checkout.session.expired").contains(type)) {
            UUID owner = parseOwner(object.path("client_reference_id").asText());
            if (owner != null && lockIfPresent(owner)) billing.refreshCheckout(owner, object.path("id").asText());
        } else if (type.startsWith("customer.subscription.") || Set.of("invoice.paid", "invoice.payment_failed").contains(type)) {
            boolean invoice = type.startsWith("invoice.");
            String reference = invoice ? object.path("subscription").asText(
                    object.path("parent").path("subscription_details").path("subscription").asText()) : object.path("id").asText();
            UUID owner = db.list("SELECT user_id FROM subscriptions WHERE provider='STRIPE' AND provider_ref=?", reference)
                    .stream().findFirst().map(row -> (UUID) row.get("userId"))
                    .orElseGet(() -> parseOwner(object.path("metadata").path("careerforge_user").asText()));
            if (owner != null && lockIfPresent(owner)) {
                billing.refreshSubscription(owner, reference);
                if ("invoice.paid".equals(type) && "PRO".equals(billing.plan(owner).get("plan"))) {
                    notifications.create(owner, "PAYMENT_CONFIRMED", "Subscription payment received", "Your CareerForge invoice was paid.",
                            "/settings", "billing:invoice:" + object.path("id").asText(id), true);
                }
            }
        }
        return Map.of("received", true);
    }

    private boolean lockIfPresent(UUID owner) {
        return !db.list("SELECT id FROM app_users WHERE id=? AND deleted_at IS NULL FOR UPDATE", owner).isEmpty();
    }

    private UUID parseOwner(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean valid(String body, String header) {
        if (webhookSecret.isBlank() || header == null || header.length() > 2000) return false;
        try {
            var fields = Arrays.stream(header.split(",")).map(String::strip).toList();
            long at = Long.parseLong(fields.stream().filter(s -> s.startsWith("t=")).findFirst().orElseThrow().substring(2));
            long now = Instant.now().getEpochSecond();
            if (at < now - 300 || at > now + 300) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((at + "." + body).getBytes(StandardCharsets.UTF_8));
            return fields.stream().filter(s -> s.startsWith("v1=") && s.length() == 67 && s.substring(3).matches("[0-9A-Fa-f]+"))
                    .anyMatch(s -> MessageDigest.isEqual(digest, HexFormat.of().parseHex(s.substring(3))));
        } catch (Exception e) {
            return false;
        }
    }
}
