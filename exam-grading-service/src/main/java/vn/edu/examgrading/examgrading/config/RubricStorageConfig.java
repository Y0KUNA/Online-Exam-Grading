package vn.edu.examgrading.examgrading.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.examgrading.examgrading.storage.RubricFileStorage;
import vn.edu.examgrading.examgrading.storage.RubricStorageProperties;

@Configuration
public class RubricStorageConfig {
    @Bean
    RubricStorageProperties rubricStorageProperties(
            @Value("${app.rubric-storage.endpoint}") String endpoint,
            @Value("${app.rubric-storage.region:us-east-1}") String region,
            @Value("${app.rubric-storage.access-key}") String accessKey,
            @Value("${app.rubric-storage.secret-key}") String secretKey,
            @Value("${app.rubric-storage.bucket:rubrics}") String bucket,
            @Value("${app.rubric-storage.gateway-base}") String gatewayBase,
            @Value("${app.rubric-storage.max-pdf-bytes:52428800}") long maxPdfBytes,
            @Value("${app.rubric-storage.upload-ttl-minutes:10}") int uploadTtlMinutes) {
        return new RubricStorageProperties(endpoint, region, accessKey, secretKey, bucket, gatewayBase, maxPdfBytes, uploadTtlMinutes);
    }

    @Bean
    RubricFileStorage rubricFileStorage(RubricStorageProperties props, java.time.Clock clock) {
        MinioClient client = MinioClient.builder().endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey()).region(props.region()).build();
        return new RubricFileStorage(client, props, clock);
    }
}
