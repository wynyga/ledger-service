package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.IdempotencyKeyTakenException;
import io.github.wynyga.ledger.application.TransferStore;
import io.github.wynyga.ledger.domain.Entry;
import io.github.wynyga.ledger.domain.Transfer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

@Component
class JpaTransferStore implements TransferStore {
    static final String KEY_CONSTRAINT = "uq_transfers_idempotency_key";

    private final TransferJpaRepository transfers;
    private final EntryJpaRepository entries;
    private final EntityManager em;

    JpaTransferStore(TransferJpaRepository transfers, EntryJpaRepository entries, EntityManager em) {
        this.transfers = transfers;
        this.entries = entries;
        this.em = em;
    }

    @Override
    public Optional<Transfer> find(UUID id) {
        return transfers.findById(id).map(this::withEntries);
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String key) {
        return transfers.findByIdempotencyKey(key).map(this::withEntries);
    }

    @Override
    public void insert(Transfer transfer) {
        try {
            em.persist(TransferEntity.from(transfer));
            // Flush now so a duplicate key surfaces here, as a typed exception, and not at commit.
            em.flush();
        } catch (PersistenceException e) {
            if (isKeyViolation(e)) {
                throw new IdempotencyKeyTakenException(transfer.idempotencyKey(), e);
            }
            throw e;
        }
        for (Entry entry : transfer.entries()) {
            em.persist(EntryEntity.from(entry));
        }
    }

    private Transfer withEntries(TransferEntity t) {
        return t.toDomain(entries.findByTransferIdOrderByIdAsc(t.id()).stream().map(EntryEntity::toDomain).toList());
    }

    private static boolean isKeyViolation(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ConstraintViolationException cv && KEY_CONSTRAINT.equals(cv.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
