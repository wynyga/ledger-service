package io.github.wynyga.ledger.application;

import io.github.wynyga.ledger.application.TokenIssuer.AccessToken;
import io.github.wynyga.ledger.domain.ErrorCode;
import io.github.wynyga.ledger.domain.LedgerException;
import io.github.wynyga.ledger.domain.Role;
import io.github.wynyga.ledger.domain.User;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Use cases: register, sign in, and create the first admin. */
public class AuthService {
    private final UserStore users;
    private final PasswordHasher hasher;
    private final TokenIssuer tokens;
    private final Clock clock;
    /** Checked against when the username does not exist, so a miss takes as long as a wrong password. */
    private final String dummyHash;

    public AuthService(UserStore users, PasswordHasher hasher, TokenIssuer tokens, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyHash = hasher.hash("timing-equaliser-" + UUID.randomUUID());
    }

    public User register(String username, String password) {
        return create(username, password, Role.USER);
    }

    /**
     * Same answer for an unknown user and a wrong password, so the endpoint cannot be used to find out which
     * usernames exist.
     */
    public AccessToken login(String username, String password) {
        Optional<User> user;
        try {
            user = users.findByUsername(User.normalizeUsername(username));
        } catch (LedgerException e) {
            user = Optional.empty();
        }
        String raw = password == null ? "" : password;
        // Always run one hash check, so a missing user answers as slowly as a wrong password.
        boolean ok = hasher.matches(raw, user.map(User::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            throw new LedgerException(ErrorCode.INVALID_CREDENTIALS, "username or password is wrong");
        }
        return tokens.issue(user.get());
    }

    /** Creates the admin at startup when it does not exist yet. Returns true if it was created. */
    public boolean ensureAdmin(String username, String password) {
        if (users.findByUsername(User.normalizeUsername(username)).isPresent()) {
            return false;
        }
        try {
            create(username, password, Role.ADMIN);
            return true;
        } catch (LedgerException e) {
            if (e.code() == ErrorCode.USERNAME_TAKEN) {
                return false; // another instance created it first
            }
            throw e;
        }
    }

    private User create(String username, String password, Role role) {
        String name = User.normalizeUsername(username);
        User.checkPassword(password);
        User user = new User(UUID.randomUUID(), name, hasher.hash(password), role, clock.instant());
        users.insert(user);
        return user;
    }
}
