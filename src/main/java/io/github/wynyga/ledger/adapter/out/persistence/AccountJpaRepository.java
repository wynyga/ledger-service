package io.github.wynyga.ledger.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    /** {@code SELECT ... FOR UPDATE}: held until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> lockById(UUID id);

    @Query("select a.id from AccountEntity a where a.systemCode = :code")
    Optional<UUID> findIdBySystemCode(String code);
}
