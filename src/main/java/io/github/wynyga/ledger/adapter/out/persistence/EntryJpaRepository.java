package io.github.wynyga.ledger.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface EntryJpaRepository extends JpaRepository<EntryEntity, Long> {

    List<EntryEntity> findByTransferIdOrderByIdAsc(UUID transferId);

    /** Newest first; backed by the index on (account_id, id desc). */
    List<EntryEntity> findByAccountIdOrderByIdDesc(UUID accountId, Limit limit);
}
