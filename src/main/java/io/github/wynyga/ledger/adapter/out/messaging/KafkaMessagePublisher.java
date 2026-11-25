package io.github.wynyga.ledger.adapter.out.messaging;

import io.github.wynyga.ledger.application.MessagePublisher;
import io.github.wynyga.ledger.application.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends one outbox row and waits for the broker's acknowledgement ({@code acks=all}, idempotent producer), so
 * "returned normally" means "stored by the broker".
 */
public class KafkaMessagePublisher implements MessagePublisher {
    public static final String EVENT_ID = "event-id";
    public static final String EVENT_TYPE = "event-type";

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration timeout;

    public KafkaMessagePublisher(KafkaTemplate<String, String> kafka, String topic, Duration timeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(OutboxMessage m) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, m.key(), m.payload());
        record.headers().add(EVENT_ID, m.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE, m.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("interrupted while sending event " + m.eventId(), e);
        } catch (ExecutionException e) {
            throw new PublishFailedException("broker refused event " + m.eventId() + ": " + e.getCause(), e);
        } catch (TimeoutException e) {
            throw new PublishFailedException("no acknowledgement for event " + m.eventId() + " in " + timeout, e);
        } catch (RuntimeException e) {
            // e.g. metadata not available within max.block.ms while the broker is down
            throw new PublishFailedException("could not send event " + m.eventId() + ": " + e.getMessage(), e);
        }
    }
}
