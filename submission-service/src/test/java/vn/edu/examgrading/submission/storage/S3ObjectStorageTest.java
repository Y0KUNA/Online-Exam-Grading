package vn.edu.examgrading.submission.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway.StoredObject;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway.UploadTicket;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/**
 * TICKET 02 evidence: exercises the real MinIO at http://localhost:9000
 * (no Testcontainers). Requires the Compose MinIO from TICKET 01 to be up.
 */
class S3ObjectStorageTest {

    private static final byte[] PDF =
            ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                    .getBytes(StandardCharsets.UTF_8);

    private static S3ObjectStorage gateway() {
        StorageProperties props = new StorageProperties(
                "s3",
                env("S3_INTERNAL_ENDPOINT", "http://localhost:9000"),
                env("S3_REGION", "us-east-1"),
                env("S3_ACCESS_KEY", "minioadmin"),
                env("S3_SECRET_KEY", "change-me"),
                env("S3_BUCKET", "original"),
                "anonymized",
            env("S3_PUBLIC_BASE", "http://localhost:8080/storage"),
                52428800L, 1024L);
        return S3ObjectStorage.create(props, Clock.systemUTC());
    }

    @Test
    void putHeadDeleteRoundTripAgainstRealMinio() {
        S3ObjectStorage gw = gateway();
        String key = ObjectKeyFactory.of(UUID.randomUUID(), UUID.randomUUID(), 1);

        gw.put(key, PDF, "application/pdf");
        StoredObject stored = gw.head(key).orElseThrow();
        assertThat(stored.sizeBytes()).isEqualTo(PDF.length);

        UploadTicket ticket = gw.presignPost(key, PDF.length, Duration.ofMinutes(10));
        assertThat(ticket.url()).isEqualTo("http://localhost:8080/storage/original");
        assertThat(ticket.fields()).containsKeys(
                "key", "Content-Type", "policy", "x-amz-algorithm", "x-amz-credential", "x-amz-date", "x-amz-signature");
        assertThat(ticket.fields()).containsEntry("key", key);
        assertThat(ticket.fields()).containsEntry("Content-Type", "application/pdf");

        gw.delete(key);
        assertThat(gw.head(key)).isEmpty();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
