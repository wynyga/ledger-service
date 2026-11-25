package io.github.wynyga.ledger.application;

import java.util.List;

/** Port: sends messages to the broker and reports what the broker acknowledged. */
public interface MessagePublisher {

    /**
     * What happened to a batch.
     *
     * @param acknowledged how many messages from the start of the batch the broker acknowledged, in order
     * @param failure      why the next one was not, or null when all were
     */
    record Outcome(int acknowledged, RuntimeException failure) {
    }

    /** @throws PublishFailedException when the broker did not acknowledge in time */
    void publish(OutboxMessage message);

    /**
     * Sends a batch. Implementations may send in parallel, but must report only an unbroken prefix as acknowledged;
     * messages after a failure may or may not have arrived and are sent again later (at least once).
     */
    default Outcome publishAll(List<OutboxMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            try {
                publish(messages.get(i));
            } catch (RuntimeException e) {
                return new Outcome(i, e);
            }
        }
        return new Outcome(messages.size(), null);
    }

    class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
