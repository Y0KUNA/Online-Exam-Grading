package vn.edu.examgrading.resultreview.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Beans for the §9.3 retention purge: HTTP client and injectable clock. */
@Configuration
public class RetentionConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
