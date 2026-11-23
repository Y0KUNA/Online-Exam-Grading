package vn.edu.examgrading.submission.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated scheduler pool for the ops jobs (Ticket 07): upload-abandonment
 * sweep, orphan-object scan and the processing-job reconciler. The default
 * Spring scheduler is single-threaded — one slow sweep would delay the others,
 * so jobs get their own small pool ({@code app.jobs.pool-size}, default 4).
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler(
            @Value("${app.jobs.pool-size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("submission-job-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
