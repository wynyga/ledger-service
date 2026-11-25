package io.github.wynyga.ledger.config;

import io.github.wynyga.ledger.adapter.out.messaging.KafkaMessagePublisher;
import io.github.wynyga.ledger.application.MessagePublisher;
import io.github.wynyga.ledger.application.OutboxRelay;
import io.github.wynyga.ledger.application.OutboxStore;
import io.github.wynyga.ledger.application.TransactionRunner;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Outbox relay wiring: topic, publisher, relay and its backlog metric. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OutboxConfig {

    /** Created at startup by Spring Kafka's admin if missing. Use 3 replicas on a real cluster. */
    @Bean
    NewTopic transfersTopic(@Value("${ledger.outbox.topic}") String topic,
            @Value("${ledger.outbox.topic-partitions:3}") int partitions,
            @Value("${ledger.outbox.topic-replicas:1}") short replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    MessagePublisher messagePublisher(KafkaTemplate<String, String> kafka, @Value("${ledger.outbox.topic}") String topic,
            @Value("${ledger.outbox.send-timeout:10s}") Duration timeout) {
        return new KafkaMessagePublisher(kafka, topic, timeout);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore store, MessagePublisher publisher, TransactionRunner tx, Clock clock,
            @Value("${ledger.outbox.relay.batch-size:100}") int batchSize) {
        return new OutboxRelay(store, publisher, tx, clock, batchSize);
    }

    /** {@code ledger_outbox_pending}: events written but not yet acknowledged by Kafka. Alert when it keeps growing. */
    @Bean
    MeterBinder outboxMetrics(OutboxRelay relay) {
        return registry -> Gauge.builder("ledger.outbox.pending", relay, OutboxRelay::pending)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }
}
