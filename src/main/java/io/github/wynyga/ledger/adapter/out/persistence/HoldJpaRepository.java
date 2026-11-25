package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.domain.HoldStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface HoldJpaRepository extends JpaRepository<HoldEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HoldEntity h where h.id = :id")
    Optional<HoldEntity> lockById(UUID id);

    Optional<HoldEntity> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);

    /** Backed by the partial index on expires_at where status = 'AUTHORIZED'. */
    @Query("select h.id from HoldEntity h where h.status = :status and h.expiresAt <= :now order by h.expiresAt")
    List<UUID> findIdsDue(HoldStatus status, Instant now, Limit limit);
}
