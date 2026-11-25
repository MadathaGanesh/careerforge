package com.careerforge.core;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class WorkflowMetrics {
    public WorkflowMetrics(Db db, MeterRegistry registry) {
        gauge(registry, db, "careerforge.ai.queued", "SELECT count(*) FROM ai_jobs WHERE status='QUEUED'");
        gauge(registry, db, "careerforge.ai.failed", "SELECT count(*) FROM ai_jobs WHERE status='FAILED'");
        gauge(registry, db, "careerforge.ai.expired.leases",
                "SELECT count(*) FROM ai_jobs WHERE status='RUNNING' AND lease_until < now()");
        gauge(registry, db, "careerforge.ai.oldest.seconds",
                "SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(created_at)),0) FROM ai_jobs WHERE status IN ('QUEUED','RUNNING')");
        gauge(registry, db, "careerforge.privacy.cleanup.pending", "SELECT count(*) FROM privacy_requests WHERE status='PENDING'");
        gauge(registry, db, "careerforge.email.failed", "SELECT count(*) FROM notification_email_outbox WHERE status='FAILED'");
    }

    private void gauge(MeterRegistry registry, Db db, String name, String sql) {
        Gauge.builder(name, db, connection -> {
            Number value = connection.jdbc.queryForObject(sql, Double.class);
            return value == null ? Double.NaN : value.doubleValue();
        }).strongReference(true).register(registry);
    }
}
