package io.github.wynyga.ledger.application;

/** Port: sends one message to the broker and returns only once the broker has acknowledged it. */
public interface MessagePublisher {

    /** @throws PublishFailedException when the broker did not acknowledge in time */
    void publish(OutboxMessage message);

    class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
