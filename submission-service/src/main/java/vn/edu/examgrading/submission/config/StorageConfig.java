package vn.edu.examgrading.submission.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.examgrading.submission.storage.InMemoryObjectStorage;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.S3ObjectStorage;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/** Wires the {@link ObjectStorageGateway} from {@code app.storage.*}/{@code app.upload.*}. */
@Configuration
public class StorageConfig {

    @Bean
    public StorageProperties storageProperties(
            @Value("${app.storage.mode:s3}") String mode,
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region:us-east-1}") String region,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.bucket:original}") String bucket,
            @Value("${app.upload.gateway-base}") String gatewayBase,
            @Value("${app.upload.max-pdf-bytes:52428800}") long maxPdfBytes,
            @Value("${app.upload.size-tolerance-bytes:1024}") long sizeToleranceBytes) {
        return new StorageProperties(mode, endpoint, region, accessKey, secretKey, bucket,
                gatewayBase, maxPdfBytes, sizeToleranceBytes);
    }

    @Bean
    public ObjectStorageGateway objectStorageGateway(StorageProperties props, Clock clock) {
        if ("memory".equalsIgnoreCase(props.mode())) {
            return new InMemoryObjectStorage();
        }
        return S3ObjectStorage.create(props, clock);
    }
}
