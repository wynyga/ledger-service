package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.domain.User;
import java.util.Optional;

/** Port: registered users. */
public interface UserStore {

    /** @throws io.github.wynyga.ledger.domain.LedgerException USERNAME_TAKEN when the username exists */
    void insert(User user);

    Optional<User> findByUsername(String username);
}
