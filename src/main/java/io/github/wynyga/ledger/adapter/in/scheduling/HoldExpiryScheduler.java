package io.github.wynyga.ledger.adapter.in.scheduling;

import io.github.wynyga.ledger.application.HoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gives back the money of holds nobody captured in time. Capture already refuses an expired hold. */
@Component
@ConditionalOnProperty(name = "ledger.holds.expiry.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);
    private static final int BATCH = 500;

    private final HoldService holds;

    HoldExpiryScheduler(HoldService holds) {
        this.holds = holds;
    }

    @Scheduled(fixedDelayString = "${ledger.holds.expiry.interval-ms:30000}")
    void run() {
        try {
            int n = holds.expireDue(BATCH);
            if (n > 0) {
                log.info("expired {} holds", n);
            }
        } catch (RuntimeException e) {
            log.warn("hold expiry could not run: {}", e.toString());
        }
    }
}
