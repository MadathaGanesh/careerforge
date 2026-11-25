package com.careerforge.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class StripeSubscriptionSync {
    private static final Logger log = LoggerFactory.getLogger(StripeSubscriptionSync.class);
    private final Db db;
    private final StripeClient stripe;
    private final TransactionTemplate tx;
    private final String origin;

    public StripeSubscriptionSync(Db db, StripeClient stripe, TransactionTemplate tx, @Value("${app.public-origin}") String origin) {
        this.db = db;
        this.stripe = stripe;
        this.tx = tx;
        this.origin = origin;
    }

    public Map<String, Object> checkout(UUID owner) {
        if (!stripe.configured()) throw new ApiError(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "BILLING_UNCONFIGURED", "Payment provider not configured");
        tx.executeWithoutResult(status -> {
            db.lockUser(owner);
            var plan = plan(owner);
            if (plan.get("checkoutSession") != null) refreshCheckout(owner, (String) plan.get("checkoutSession"));
        });
        UUID key = tx.execute(status -> {
            db.lockUser(owner);
            var plan = plan(owner);
            if (activeLink(plan)) throw ApiError.conflict("Manage your existing subscription from the billing portal.");
            if (plan.get("checkoutKey") instanceof UUID pending) return pending;
            UUID created = UUID.randomUUID();
            db.jdbc.update("UPDATE subscriptions SET checkout_key=?,checkout_started_at=now() WHERE user_id=?", created, owner);
            return created;
        });
        return tx.execute(status -> {
            db.lockUser(owner);
            var plan = plan(owner);
            if (activeLink(plan) || !key.equals(plan.get("checkoutKey"))) throw ApiError.conflict("Checkout changed. Refresh Settings.");
            if (plan.get("checkoutUrl") instanceof String url) return Map.of("url", url);
            if (db.list("SELECT user_id FROM subscriptions WHERE user_id=? AND checkout_started_at>now()-interval '23 hours'", owner).isEmpty()) {
                throw ApiError.conflict("Checkout status needs reconciliation. Contact support before creating another checkout.");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("mode", "subscription");
            form.put("line_items[0][price]", stripe.price());
            form.put("line_items[0][quantity]", "1");
            form.put("client_reference_id", owner.toString());
            form.put("subscription_data[metadata][careerforge_user]", owner.toString());
            form.put("success_url", origin + "/settings?checkout=success");
            form.put("cancel_url", origin + "/settings?checkout=cancelled");
            if (plan.get("providerCustomer") instanceof String customer) form.put("customer", customer);
            else form.put("customer_email", (String) db.one("SELECT email FROM app_users WHERE id=?", owner).get("email"));
            JsonNode session = stripe.post("checkout/sessions", form, "careerforge-checkout-" + key);
            String url = hostedUrl(session, "checkout.stripe.com");
            String id = session.path("id").asText();
            if (!id.matches("cs_[A-Za-z0-9_]+")) throw ApiError.invalid("Unexpected checkout response");
            db.jdbc.update("""
                    UPDATE subscriptions SET checkout_session=?,checkout_url=?,checkout_expires_at=to_timestamp(?) WHERE user_id=?
                    """, id, url, session.path("expires_at").asLong(), owner);
            return Map.of("url", url);
        });
    }

    public Map<String, Object> portal(UUID owner) {
        String customer = (String) plan(owner).get("providerCustomer");
        if (customer == null) throw ApiError.conflict("No billing customer has been linked yet");
        JsonNode session = stripe.post("billing_portal/sessions", Map.of("customer", customer, "return_url", origin + "/settings"), null);
        return Map.of("url", hostedUrl(session, "billing.stripe.com"));
    }

    public Map<String, Object> plan(UUID owner) {
        return db.one("SELECT * FROM subscriptions WHERE user_id=?", owner);
    }

    public boolean activeLink(Map<String, Object> plan) {
        return plan.get("providerRef") != null
                && !Set.of("CANCELED", "INCOMPLETE_EXPIRED").contains(plan.get("status"));
    }

    public void refreshCheckout(UUID owner, String sessionId) {
        var current = plan(owner);
        if (current.get("checkoutSession") != null && !sessionId.equals(current.get("checkoutSession"))) return;
        var session = stripe.get("checkout/sessions", sessionId, "cs_");
        if (!owner.toString().equals(session.path("client_reference_id").asText())) return;
        String status = session.path("status").asText();
        if ("expired".equals(status)) {
            clearCheckout(owner);
        } else if ("complete".equals(status) && session.path("subscription").isTextual()) {
            refreshSubscription(owner, session.path("subscription").asText());
            clearCheckout(owner);
        }
    }

    public void refreshSubscription(UUID owner, String reference) {
        var subscription = stripe.get("subscriptions", reference, "sub_");
        var current = plan(owner);
        if (current.get("providerRef") != null && !reference.equals(current.get("providerRef")) && activeLink(current)) return;
        if (!reference.equals(current.get("providerRef"))
                && !owner.toString().equals(subscription.path("metadata").path("careerforge_user").asText())) return;
        StripeEntitlement entitlement = StripeEntitlement.from(subscription, stripe.price());
        db.jdbc.update("""
                UPDATE subscriptions SET provider='STRIPE',provider_ref=?,provider_customer=?,plan=?,status=?,
                period_end=?,provider_checked_at=now(),updated_at=now() WHERE user_id=?
                """, reference, subscription.path("customer").asText(null), entitlement.plan(), entitlement.status(),
                entitlement.periodEnd() == null ? null : Timestamp.from(entitlement.periodEnd()), owner);
    }

    private void clearCheckout(UUID owner) {
        db.jdbc.update("""
                UPDATE subscriptions SET checkout_key=NULL,checkout_started_at=NULL,checkout_session=NULL,
                checkout_url=NULL,checkout_expires_at=NULL WHERE user_id=?
                """, owner);
    }

    public void prepareDeletion(UUID owner) {
        var plan = plan(owner);
        if (plan.get("checkoutSession") instanceof String session) refreshCheckout(owner, session);
        plan = plan(owner);
        if (activeLink(plan)) refreshSubscription(owner, (String) plan.get("providerRef"));
    }

    @Scheduled(fixedDelayString = "${app.stripe-reconcile-ms:3600000}", initialDelayString = "PT3M")
    public void reconcile() {
        if (!stripe.configured()) return;
        var rows = db.list("""
                SELECT user_id FROM subscriptions WHERE checkout_session IS NOT NULL
                OR (provider_ref IS NOT NULL AND status NOT IN ('CANCELED','INCOMPLETE_EXPIRED'))
                ORDER BY provider_checked_at NULLS FIRST LIMIT 50
                """);
        for (var row : rows) {
            UUID owner = (UUID) row.get("userId");
            try {
                tx.executeWithoutResult(status -> {
                    db.lockUser(owner);
                    prepareDeletion(owner);
                    db.jdbc.update("UPDATE subscriptions SET provider_checked_at=now() WHERE user_id=?", owner);
                });
            } catch (RuntimeException e) {
                log.warn("Billing reconciliation failed account={} category={}", owner, e.getClass().getSimpleName());
                db.jdbc.update("UPDATE subscriptions SET provider_checked_at=now() WHERE user_id=?", owner);
            }
        }
    }

    private String hostedUrl(JsonNode body, String host) {
        String url = body.path("url").asText();
        if (!url.startsWith("https://" + host + "/")) throw ApiError.invalid("Unexpected billing redirect");
        return url;
    }
}
