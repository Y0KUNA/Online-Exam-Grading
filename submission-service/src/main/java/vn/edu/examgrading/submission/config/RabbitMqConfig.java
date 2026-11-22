package vn.edu.examgrading.submission.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable validate/anonymize pipelines with per-step delayed retry queues
 * (spec §8, D-18). Retry queues hold messages via {@code x-message-ttl} and
 * dead-letter back to {@code submission.processing} with the original routing
 * key once the delay expires.
 */
@Configuration
public class RabbitMqConfig {

    private static final String EXCHANGE = "submission.processing";

    /**
     * Both RabbitTemplate and @RabbitListener use this converter.  Job records
     * are not Java Serializable; without a JSON converter the upload would be
     * marked UPLOADED but no validate/anonymize message could be published.
     */
    @Bean MessageConverter rabbitMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean DirectExchange processingExchange() { return new DirectExchange(EXCHANGE, true, false); }

    @Bean Queue validateQueue() { return new Queue("submission.validate.v1", true); }
    @Bean Queue anonymizeQueue() { return new Queue("submission.anonymize.v1", true); }
    @Bean Queue validateDlq() { return new Queue("submission.validate.dlq", true); }
    @Bean Queue anonymizeDlq() { return new Queue("submission.anonymize.dlq", true); }

    @Bean Binding validateBinding(Queue validateQueue, DirectExchange processingExchange) {
        return BindingBuilder.bind(validateQueue).to(processingExchange).with("validate");
    }
    @Bean Binding anonymizeBinding(Queue anonymizeQueue, DirectExchange processingExchange) {
        return BindingBuilder.bind(anonymizeQueue).to(processingExchange).with("anonymize");
    }

    // Retry queues per routing key: TTL 30s / 2m / 10m, then DLX back to the main exchange.
    @Bean Queue validateRetry30s() { return retryQueue("submission.validate.retry.30s", 30_000L, "validate"); }
    @Bean Queue validateRetry2m() { return retryQueue("submission.validate.retry.2m", 120_000L, "validate"); }
    @Bean Queue validateRetry10m() { return retryQueue("submission.validate.retry.10m", 600_000L, "validate"); }
    @Bean Queue anonymizeRetry30s() { return retryQueue("submission.anonymize.retry.30s", 30_000L, "anonymize"); }
    @Bean Queue anonymizeRetry2m() { return retryQueue("submission.anonymize.retry.2m", 120_000L, "anonymize"); }
    @Bean Queue anonymizeRetry10m() { return retryQueue("submission.anonymize.retry.10m", 600_000L, "anonymize"); }

    private static Queue retryQueue(String name, long ttlMs, String routingKey) {
        return QueueBuilder.durable(name)
            .withArgument("x-message-ttl", ttlMs)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", routingKey)
            .build();
    }
}
