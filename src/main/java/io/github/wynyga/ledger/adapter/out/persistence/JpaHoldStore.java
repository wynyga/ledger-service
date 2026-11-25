package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.HoldStore;
import io.github.wynyga.ledger.application.IdempotencyKeyTakenException;
import io.github.wynyga.ledger.domain.Hold;
import io.github.wynyga.ledger.domain.HoldStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
class JpaHoldStore implements HoldStore {
    static final String KEY_CONSTRAINT = "uq_holds_requester_key";

    private final HoldJpaRepository holds;
    private final EntityManager em;

    JpaHoldStore(HoldJpaRepository holds, EntityManager em) {
        this.holds = holds;
        this.em = em;
    }

    @Override
    public void insert(Hold hold) {
        try {
            em.persist(HoldEntity.from(hold));
            em.flush();
        } catch (PersistenceException e) {
            if (Constraints.violates(e, KEY_CONSTRAINT)) {
                throw new IdempotencyKeyTakenException(hold.idempotencyKey(), e);
            }
            throw e;
        }
    }

    @Override
    public Optional<Hold> find(UUID id) {
        return holds.findById(id).map(HoldEntity::toDomain);
    }

    @Override
    public Optional<Hold> lock(UUID id) {
        return holds.lockById(id).map(HoldEntity::toDomain);
    }

    @Override
    public Optional<Hold> findByIdempotencyKey(UUID requestedBy, String key) {
        return holds.findByRequestedByAndIdempotencyKey(requestedBy, key).map(HoldEntity::toDomain);
    }

    @Override
    public void update(Hold hold) {
        em.find(HoldEntity.class, hold.id()).copyState(hold);
    }

    @Override
    public List<UUID> dueForExpiry(Instant now, int limit) {
        return holds.findIdsDue(HoldStatus.AUTHORIZED, now, Limit.of(limit));
    }
}
