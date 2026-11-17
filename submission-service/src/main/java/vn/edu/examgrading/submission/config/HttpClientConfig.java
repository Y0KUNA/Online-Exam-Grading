package vn.edu.examgrading.submission.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Shared synchronous HTTP client for service-to-service clients. */
@Configuration
public class HttpClientConfig {

    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
