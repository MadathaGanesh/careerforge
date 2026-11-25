package com.careerforge.documents;

import com.careerforge.core.Db;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Duration;

@Service
public class StorageJanitor {
    private static final Logger log = LoggerFactory.getLogger(StorageJanitor.class);
    private final Db db;
    private final DocumentStorage storage;
    private final TransactionTemplate tx;
    private final Duration grace;
    private String localCursor;
    private String remoteCursor;

    public StorageJanitor(Db db, DocumentStorage storage, TransactionTemplate tx,
                          @Value("${app.storage-orphan-grace:PT24H}") Duration grace) {
        this.db = db;
        this.storage = storage;
        this.tx = tx;
        this.grace = grace.compareTo(Duration.ofMinutes(5)) < 0 ? Duration.ofMinutes(5) : grace;
    }

    @Scheduled(fixedDelayString = "${app.storage-janitor-delay:PT10M}",
            initialDelayString = "${app.storage-janitor-initial-delay:PT10M}")
    public void collect() {
        Instant olderThan = Instant.now().minus(grace);
        try {
            var local = storage.localCandidates(localCursor);
            sweep(local, olderThan);
            localCursor = local.next();
        } catch (RuntimeException e) {
            log.warn("Local orphan scan delayed category={}", e.getClass().getSimpleName());
        }
        try {
            var remote = storage.remoteCandidates(remoteCursor);
            sweep(remote, olderThan);
            remoteCursor = remote.next();
        } catch (RuntimeException e) {
            log.warn("Remote orphan scan delayed category={}", e.getClass().getSimpleName());
            remoteCursor = null;
        }
    }

    private void sweep(DocumentStorage.ScanPage batch, Instant olderThan) {
        for (var candidate : batch.objects()) {
            if (!candidate.updatedAt().isBefore(olderThan)) continue;
            try {
                tx.executeWithoutResult(status -> {
                    // Upload, export, and account deletion use the same owner lock.
                    db.list("SELECT id FROM app_users WHERE id=? FOR UPDATE", candidate.owner());
                    String previousKey = storage.legacyKey(candidate);
                    boolean linked = Boolean.TRUE.equals(db.jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM documents WHERE storage_key IN (?,?)
                            UNION ALL SELECT 1 FROM exports WHERE storage_key IN (?,?)
                            UNION ALL SELECT 1 FROM jake_resume_exports WHERE storage_key IN (?,?))
                            """, Boolean.class, candidate.key(), previousKey, candidate.key(), previousKey,
                            candidate.key(), previousKey));
                    if (!linked) storage.delete(candidate.owner(), candidate.key());
                });
            } catch (RuntimeException e) {
                log.warn("Orphan candidate remains queued for a later scan category={}", e.getClass().getSimpleName());
            }
        }
    }
}
