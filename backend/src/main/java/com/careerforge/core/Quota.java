package com.careerforge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class Quota {
    private static final Logger log = LoggerFactory.getLogger(Quota.class);

    private final Db db;

    public Quota(Db db) {
        this.db = db;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void consume(String event) {
        log.debug("Entering Quota.consume");
        db.lockUser(db.uid());
        String plan = String.valueOf(db.one("SELECT plan FROM subscriptions WHERE user_id=?", db.uid()).get("plan"));
        int max = plan.equals("PRO") ? 100 : switch (event) {
            case "ANALYSIS" -> 3;
            case "TAILORING" -> 2;
            case "INTERVIEW_QUESTIONS" -> 3;
            default -> 30;
        };
        Integer count = db.jdbc.queryForObject("SELECT count(*) FROM usage_events WHERE user_id=? AND event_type=? AND created_at>=date_trunc('month',now())", Integer.class, db.uid(), event);
        log.debug("Quota checked event={} plan={} used={} limit={}", event, plan, count, max);
        if (count != null && count >= max) {
            log.warn("Quota exceeded event={} plan={} used={} limit={}", event, plan, count, max);
            throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "PLAN_LIMIT", "Monthly usage limit reached for " + event + ". Review your plan in Settings.");
        }
        db.jdbc.update("INSERT INTO usage_events (id,user_id,event_type) VALUES (?,?,?)", UUID.randomUUID(), db.uid(), event);
        log.info("Quota usage recorded event={}", event);
    }
}

