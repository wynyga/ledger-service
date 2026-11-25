package io.github.wynyga.ledger.application;

import java.util.UUID;

/**
 * One unpublished outbox row.
 *
 * @param id       insertion order
 * @param eventId  stable across retries; consumers use it to drop duplicates
 * @param key      message key (the transfer id)
 * @param payload  JSON body
 */
public record OutboxMessage(long id, UUID eventId, String eventType, String key, String payload, int attempts) {
}
