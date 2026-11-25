package io.github.wynyga.ledger.adapter.out.messaging;

import io.github.wynyga.ledger.application.MessagePublisher;
import io.github.wynyga.ledger.application.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Sends outbox rows and waits for the broker's acknowledgement ({@code acks=all}, idempotent producer), so
 * "acknowledged" means "stored by the broker".
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
        Outcome o = publishAll(List.of(m));
        if (o.failure() != null) {
            throw o.failure();
        }
    }

    /**
     * Hands the whole batch to the producer first, so it can batch and pipeline the sends, then waits for the
     * acknowledgements in order. One round trip per batch instead of one per message.
     */
    @Override
    public Outcome publishAll(List<OutboxMessage> messages) {
        List<CompletableFuture<SendResult<String, String>>> sent = new ArrayList<>(messages.size());
        RuntimeException sendFailure = null;
        for (OutboxMessage m : messages) {
            try {
                sent.add(kafka.send(record(m)));
            } catch (RuntimeException e) {
                // e.g. no metadata within max.block.ms while the broker is down: stop handing over more
                sendFailure = new PublishFailedException("could not send event " + m.eventId() + ": "
                        + e.getMessage(), e);
                break;
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int i = 0; i < sent.size(); i++) {
            OutboxMessage m = messages.get(i);
            try {
                long left = Math.max(1, deadline - System.nanoTime());
                sent.get(i).get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(i, new PublishFailedException("interrupted while sending " + m.eventId(), e));
            } catch (ExecutionException e) {
                return new Outcome(i, new PublishFailedException(
                        "broker refused event " + m.eventId() + ": " + e.getCause(), e));
            } catch (TimeoutException e) {
                return new Outcome(i, new PublishFailedException(
                        "no acknowledgement for event " + m.eventId() + " in " + timeout, e));
            }
        }
        return new Outcome(sent.size(), sendFailure);
    }

    private ProducerRecord<String, String> record(OutboxMessage m) {
        ProducerRecord<String, String> r = new ProducerRecord<>(topic, m.key(), m.payload());
        r.headers().add(EVENT_ID, m.eventId().toString().getBytes(StandardCharsets.UTF_8));
        r.headers().add(EVENT_TYPE, m.eventType().getBytes(StandardCharsets.UTF_8));
        return r;
    }
}
