package vn.edu.examgrading.submission.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable, isolated validate/anonymize pipelines. Delayed retry is scheduled by the worker. */
@Configuration
public class RabbitMqConfig {
    @Bean DirectExchange processingExchange() { return new DirectExchange("submission.processing",true,false); }
    @Bean Queue validateQueue() { return new Queue("submission.validate.v1",true); }
    @Bean Queue anonymizeQueue() { return new Queue("submission.anonymize.v1",true); }
    @Bean Queue validateDlq() { return new Queue("submission.validate.dlq",true); }
    @Bean Queue anonymizeDlq() { return new Queue("submission.anonymize.dlq",true); }
    @Bean Binding validateBinding(Queue validateQueue,DirectExchange processingExchange) { return BindingBuilder.bind(validateQueue).to(processingExchange).with("validate"); }
    @Bean Binding anonymizeBinding(Queue anonymizeQueue,DirectExchange processingExchange) { return BindingBuilder.bind(anonymizeQueue).to(processingExchange).with("anonymize"); }
}
