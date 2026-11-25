package io.github.wynyga.ledger.adapter.out.persistence;

import io.github.wynyga.ledger.domain.Role;
import io.github.wynyga.ledger.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 32, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    static UserEntity from(User u) {
        UserEntity e = new UserEntity();
        e.id = u.id();
        e.username = u.username();
        e.passwordHash = u.passwordHash();
        e.role = u.role();
        e.createdAt = u.createdAt();
        return e;
    }

    User toDomain() {
        return new User(id, username, passwordHash, role, createdAt);
    }
}
