package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.AccountStore;
import io.github.wynyga.ledger.domain.Account;
import io.github.wynyga.ledger.domain.Entry;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaAccountStore implements AccountStore {
    private final AccountJpaRepository accounts;
    private final EntryJpaRepository entries;
    private final EntityManager em;

    JpaAccountStore(AccountJpaRepository accounts, EntryJpaRepository entries, EntityManager em) {
        this.accounts = accounts;
        this.entries = entries;
        this.em = em;
    }

    @Override
    @Transactional
    public void insert(Account account) {
        // persist, not save: the id is assigned, and save() would SELECT first to decide between insert and merge.
        em.persist(AccountEntity.from(account));
    }

    @Override
    public Optional<Account> find(UUID id) {
        return accounts.findById(id).map(AccountEntity::toDomain);
    }

    @Override
    public Optional<Account> lock(UUID id) {
        return accounts.lockById(id).map(AccountEntity::toDomain);
    }

    @Override
    public void saveBalance(Account account) {
        // The entity is already managed (locked earlier in this transaction); dirty checking writes it at commit.
        AccountEntity e = em.find(AccountEntity.class, account.id());
        e.setBalances(account.balance(), account.held());
    }

    @Override
    public Optional<UUID> fundingAccountId(String currency) {
        return accounts.findIdBySystemCode("FUNDING_" + currency);
    }

    @Override
    public List<Entry> statement(UUID accountId, int limit) {
        return entries.findByAccountIdOrderByIdDesc(accountId, Limit.of(limit)).stream()
                .map(EntryEntity::toDomain)
                .toList();
    }
}
