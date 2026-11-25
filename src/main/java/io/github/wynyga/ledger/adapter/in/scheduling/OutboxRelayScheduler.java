package io.github.wynyga.ledger.adapter.in.scheduling;

import io.github.wynyga.ledger.application.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the relay. Turn it off with {@code ledger.outbox.relay.enabled=false} (e.g. to run it elsewhere). */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay.interval-ms:500}")
    void run() {
        try {
            OutboxRelay.Result r = relay.tick();
            if (r.failed()) {
                log.warn("outbox relay: {} published, then failed ({}); backing off", r.published(), r.error());
            } else if (r.published() > 0) {
                log.debug("outbox relay: {} published", r.published());
            }
        } catch (RuntimeException e) {
            // e.g. the database is unreachable; the next run tries again
            log.warn("outbox relay could not run: {}", e.toString());
        }
    }
}
