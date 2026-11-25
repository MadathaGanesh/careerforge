package com.careerforge.core;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;

@Component
@Profile({"production", "staging"})
public class ProductionConfiguration {
    private final boolean devAuth;
    private final boolean secureCookies;
    private final String origin;
    private final String storage;
    private final String dbPassword;
    private final String mailHost;
    private final String billingKey;
    private final String billingWebhook;
    private final String billingPrice;

    public ProductionConfiguration(
            @Value("${app.dev-auth}") boolean devAuth,
            @Value("${app.cookie-secure}") boolean secureCookies,
            @Value("${app.public-origin}") String origin,
            @Value("${app.storage-root}") String storage,
            @Value("${spring.datasource.password}") String dbPassword,
            @Value("${spring.mail.host}") String mailHost,
            @Value("${stripe.secret-key}") String billingKey,
            @Value("${stripe.webhook-secret}") String billingWebhook,
            @Value("${stripe.price-pro}") String billingPrice) {
        this.devAuth = devAuth;
        this.secureCookies = secureCookies;
        this.origin = origin;
        this.storage = storage;
        this.dbPassword = dbPassword;
        this.mailHost = mailHost;
        this.billingKey = billingKey;
        this.billingWebhook = billingWebhook;
        this.billingPrice = billingPrice;
    }

    @PostConstruct
    void validate() {
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("APP_ORIGIN must be the exact HTTPS origin in production");
        }
        if (devAuth || !secureCookies) throw new IllegalStateException("Production requires secure cookies and development authentication disabled");
        if (dbPassword.isBlank() || dbPassword.equals("careerforge_dev_password") || !Path.of(storage).isAbsolute()) {
            throw new IllegalStateException("Set production database credentials and an absolute durable STORAGE_ROOT");
        }
        if (mailHost.isBlank() || mailHost.equals("localhost") || mailHost.equals("mailpit")) {
            throw new IllegalStateException("Configure a production SMTP server");
        }
        if (!billingKey.isBlank() && (billingWebhook.isBlank() || billingPrice.isBlank())) {
            throw new IllegalStateException("Set the Stripe webhook secret and price when enabling billing");
        }
    }
}
