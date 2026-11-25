package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.application.UserStore;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;
import io.github.wynyga.ledger.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaUserStore implements UserStore {
    static final String USERNAME_CONSTRAINT = "uq_users_username";

    private final UserJpaRepository users;
    private final EntityManager em;

    JpaUserStore(UserJpaRepository users, EntityManager em) {
        this.users = users;
        this.em = em;
    }

    /** The unique constraint decides, so two registrations racing for one name cannot both win. */
    @Override
    @Transactional
    public void insert(User user) {
        try {
            em.persist(UserEntity.from(user));
            em.flush();
        } catch (PersistenceException e) {
            if (Constraints.violates(e, USERNAME_CONSTRAINT)) {
                throw new LedgerException(ErrorCode.USERNAME_TAKEN, "username " + user.username() + " is taken");
            }
            throw e;
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return users.findByUsername(username).map(UserEntity::toDomain);
    }
}
