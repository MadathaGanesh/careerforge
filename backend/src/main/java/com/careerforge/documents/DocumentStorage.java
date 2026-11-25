package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DocumentStorage {
    private static final int MAX_OBJECT_BYTES = 16 * 1024 * 1024;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentStorage.class);
    private final Path root;
    private final String bucket;
    private final String prefix;
    private final S3Client s3;

    public DocumentStorage(@Value("${app.storage-root:./storage}") String root,
                           @Value("${app.storage-bucket:}") String bucket,
                           @Value("${app.storage-prefix:careerforge/}") String prefix,
                           @Value("${app.storage-region:us-east-1}") String region,
                           @Value("${app.storage-endpoint:}") String endpoint,
                           @Value("${app.storage-path-style:false}") boolean pathStyle) throws IOException {
        this.root = Path.of(root).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        this.bucket = bucket;
        if (!prefix.matches("[a-zA-Z0-9/_-]{1,100}/") || prefix.startsWith("/") || prefix.contains("//")) {
            throw new IllegalArgumentException("STORAGE_PREFIX must be a private object namespace ending in /");
        }
        this.prefix = prefix;
        if (bucket.isBlank()) {
            s3 = null;
        } else {
            var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyle)
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(8)))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                            .socketTimeout(Duration.ofSeconds(6)));
            if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
            s3 = builder.build();
        }
    }

    record Candidate(UUID owner, String key, Instant updatedAt) {}
    record ScanPage(List<Candidate> objects, String next) {}

    @PreDestroy
    void close() {
        if (s3 != null) s3.close();
    }

    public boolean remoteEnabled() {
        return s3 != null;
    }

    public String put(UUID owner, byte[] data, String extension) {
        if (!Set.of("pdf", "docx", "tex").contains(extension) || data.length == 0 || data.length > MAX_OBJECT_BYTES) {
            throw ApiError.invalid("Document format or size is outside the storage limits");
        }
        String name = UUID.randomUUID() + "." + extension;
        String relative = owner + "/" + name;
        if (s3 != null) {
            try {
                s3.putObject(b -> b.bucket(bucket).key(prefix + relative)
                        .contentType(mediaType(extension)).cacheControl("private, no-store"), RequestBody.fromBytes(data));
                return "s3:" + relative;
            } catch (SdkException e) {
                throw unavailable("upload", e);
            }
        }
        Path directory = root.resolve(owner.toString());
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Unexpected storage link");
            Path temp = Files.createTempFile(directory, ".upload-", ".tmp");
            try {
                Files.write(temp, data);
                Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return "local:" + relative;
        } catch (IOException e) {
            throw unavailable("upload", e);
        }
    }

    public String putInTransaction(UUID owner, byte[] data, String extension) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A document write requires a database transaction");
        }
        String key = put(owner, data, extension);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    delete(owner, key);
                } catch (RuntimeException e) {
                    log.warn("Rollback cleanup deferred to the storage janitor");
                }
            }
        });
        return key;
    }

    public byte[] read(UUID owner, String key) {
        if (key.startsWith("s3:")) {
            String name = remoteKey(owner, key);
            try (var data = s3.getObject(b -> b.bucket(bucket).key(name))) {
                return bounded(data);
            } catch (S3Exception e) {
                if (e.statusCode() == 404) throw ApiError.notFound();
                throw unavailable("download", e);
            } catch (SdkException | IOException e) {
                throw unavailable("download", e);
            }
        }
        Path file = localKey(owner, key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw ApiError.notFound();
        try (var data = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return bounded(data);
        } catch (NoSuchFileException e) {
            throw ApiError.notFound();
        } catch (IOException e) {
            throw unavailable("download", e);
        }
    }

    private byte[] bounded(InputStream data) throws IOException {
        byte[] bytes = data.readNBytes(MAX_OBJECT_BYTES + 1);
        if (bytes.length > MAX_OBJECT_BYTES) throw new IOException("Stored document exceeds the configured size bounds");
        return bytes;
    }

    public void delete(UUID owner, String key) {
        if (key.startsWith("s3:")) {
            String name = remoteKey(owner, key);
            try {
                s3.deleteObject(b -> b.bucket(bucket).key(name));
            } catch (SdkException e) {
                throw unavailable("delete", e);
            }
            return;
        }
        try {
            Files.deleteIfExists(localKey(owner, key));
        } catch (IOException e) {
            throw unavailable("delete", e);
        }
    }

    public void purge(UUID owner) {
        if (s3 != null) {
            String namespace = prefix + owner + "/";
            try {
                for (var page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(namespace))) {
                    for (var file : page.contents()) {
                        s3.deleteObject(b -> b.bucket(bucket).key(file.key()));
                    }
                }
            } catch (SdkException e) {
                throw unavailable("purge", e);
            }
        }
        Path directory = root.resolve(owner.toString());
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException e) {
            throw unavailable("purge", e);
        }
    }

    ScanPage remoteCandidates(String continuation) {
        if (s3 == null) return new ScanPage(List.of(), null);
        try {
            var page = s3.listObjectsV2(b -> b.bucket(bucket).prefix(prefix).maxKeys(200)
                    .continuationToken(continuation));
            var objects = page.contents().stream()
                    .map(o -> candidate("s3:", o.key().substring(prefix.length()), o.lastModified()))
                    .filter(Objects::nonNull).toList();
            return new ScanPage(objects, page.nextContinuationToken());
        } catch (SdkException e) {
            throw unavailable("list", e);
        }
    }

    ScanPage localCandidates(String previous) {
        try (var files = Files.find(root, 2, (candidate, attrs) -> attrs.isRegularFile()
                && root.relativize(candidate).getNameCount() == 2)) {
            var eligible = files.map(path -> root.relativize(path).toString())
                    .filter(name -> previous == null || name.compareTo(previous) > 0).sorted().limit(200).toList();
            var results = new ArrayList<Candidate>();
            for (String name : eligible) {
                Instant updated;
                try {
                    updated = Files.getLastModifiedTime(root.resolve(name), LinkOption.NOFOLLOW_LINKS).toInstant();
                } catch (NoSuchFileException e) {
                    continue;
                }
                var candidate = candidate("local:", name, updated);
                if (candidate != null) results.add(candidate);
            }
            return new ScanPage(results, eligible.size() == 200 ? eligible.getLast() : null);
        } catch (IOException e) {
            throw unavailable("list", e);
        }
    }

    String legacyKey(Candidate candidate) {
        return candidate.key().startsWith("local:") ? localKey(candidate.owner(), candidate.key()).toString() : candidate.key();
    }

    private Candidate candidate(String scheme, String relative, Instant date) {
        String[] parts = relative.split("/", -1);
        if (parts.length != 2 || !parts[1].matches("(?:[0-9a-f-]{36}\\.(?:pdf|docx|tex)|\\.upload-[0-9]+\\.tmp)")) return null;
        try {
            UUID owner = UUID.fromString(parts[0]);
            if (!owner.toString().equals(parts[0])) return null;
            return new Candidate(owner, scheme + relative, date);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String remoteKey(UUID owner, String key) {
        if (s3 == null) throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Object storage is not configured");
        String name = key.substring(3);
        if (candidate("s3:", name, Instant.EPOCH) == null || !name.startsWith(owner + "/")) throw ApiError.notFound();
        return prefix + name;
    }

    private Path localKey(UUID owner, String key) {
        Path directory = root.resolve(owner.toString());
        Path file;
        if (key.startsWith("local:")) {
            file = root.resolve(key.substring(6)).normalize();
        } else {
            // V1–V8 installations stored absolute filesystem paths.
            file = Path.of(key).toAbsolutePath().normalize();
        }
        if (!Objects.equals(file.getParent(), directory) || Files.isSymbolicLink(directory)) throw ApiError.notFound();
        return file;
    }

    private String mediaType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/x-tex";
        };
    }

    private ApiError unavailable(String operation, Exception cause) {
        log.warn("Storage operation failed operation={} category={}", operation, cause.getClass().getSimpleName());
        return new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Document storage is temporarily unavailable");
    }
}
