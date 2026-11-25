package io.github.wynyga.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.wynyga.ledger.application.AccountService;
import io.github.wynyga.ledger.application.AuthService;
import io.github.wynyga.ledger.application.Caller;
import io.github.wynyga.ledger.application.TransferService;
import io.github.wynyga.ledger.application.UserStore;
import io.github.wynyga.ledger.domain.LedgerException;
import io.github.wynyga.ledger.domain.Transfer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** End to end: a committed transfer reaches a real Kafka broker through the outbox; a refused one never does. */
class OutboxTests extends IntegrationTest {

    @Autowired
    AccountService accounts;
    @Autowired
    TransferService transfers;
    @Autowired
    AuthService auth;
    @Autowired
    UserStore users;
    @Autowired
    KafkaContainer kafka;
    @Autowired
    DataSource dataSource;
    @Autowired
    JsonMapper json;

    @Value("${ledger.outbox.topic}")
    String topic;

    @Test
    void aTransferArrivesOnTheTopicWithItsEventId() throws Exception {
        Caller alice = newUser();
        UUID a = funded(alice, 10_000);
        UUID b = accounts.open(newUser(), "IDR").id();

        Transfer t = transfers.transfer(alice, "outbox-" + UUID.randomUUID(), a, b, 2_500, "IDR").transfer();

        ConsumerRecord<String, String> rec = consume(t.id().toString(), Duration.ofSeconds(30));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(rec.value(), Map.class);
        assertThat(body)
                .containsEntry("eventType", "TransferPosted")
                .containsEntry("transferId", t.id().toString())
                .containsEntry("transferType", "TRANSFER")
                .containsEntry("debitAccountId", a.toString())
                .containsEntry("creditAccountId", b.toString())
                .containsEntry("amount", 2_500)
                .containsEntry("currency", "IDR")
                .containsEntry("requestedBy", alice.userId().toString());
        assertThat(header(rec, "event-type")).isEqualTo("TransferPosted");
        assertThat(header(rec, "event-id")).isEqualTo(body.get("eventId"));

        // Marked published once Kafka acknowledged it.
        assertThat(waitForPublished(t.id(), Duration.ofSeconds(10))).isNotNull();
    }

    @Test
    void aRefusedTransferLeavesNoEvent() {
        Caller alice = newUser();
        UUID a = funded(alice, 100);
        UUID b = accounts.open(newUser(), "IDR").id();

        assertThatThrownBy(() -> transfers.transfer(alice, "outbox-" + UUID.randomUUID(), a, b, 500, "IDR"))
                .isInstanceOf(LedgerException.class);

        long events = jdbc().sql("select count(*) from outbox where payload->>'debitAccountId' = :a")
                .param("a", a.toString()).query(Long.class).single();
        assertThat(events).isZero();
        long deposits = jdbc().sql("select count(*) from outbox where payload->>'creditAccountId' = :a")
                .param("a", a.toString()).query(Long.class).single();
        assertThat(deposits).as("the deposit that funded it did produce one").isEqualTo(1);
    }

    // --- helpers

    ConsumerRecord<String, String> consume(String key, Duration timeout) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no message with key " + key + " on " + topic + " within " + timeout);
    }

    Instant waitForPublished(UUID transferId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            var at = jdbc().sql("select published_at from outbox where aggregate_id = :id")
                    .param("id", transferId).query(java.sql.Timestamp.class).optional();
            if (at.isPresent() && at.get() != null) {
                return at.get().toInstant();
            }
            Thread.sleep(200);
        }
        return null;
    }

    static String header(ConsumerRecord<String, String> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    Caller newUser() {
        String name = "o" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return new Caller(auth.register(name, PASSWORD).id(), false);
    }

    UUID funded(Caller owner, long amount) {
        UUID id = accounts.open(owner, "IDR").id();
        Caller admin = new Caller(users.findByUsername(ADMIN).orElseThrow().id(), true);
        transfers.deposit(admin, "fund-" + UUID.randomUUID(), id, amount);
        return id;
    }
}
