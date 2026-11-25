package io.github.wynyga.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void usernamesAreNormalisedToLowerCase() {
        assertThat(User.normalizeUsername("  Wayan.K_01 ")).isEqualTo("wayan.k_01");
    }

    @Test
    void usernameRules() {
        for (String bad : new String[] {null, "", "ab", "-starts-with-dash", "has space", "a".repeat(33), "ümlaut"}) {
            assertThatThrownBy(() -> User.normalizeUsername(bad)).as(String.valueOf(bad))
                    .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.USERNAME_INVALID);
        }
    }

    @Test
    void passwordRules() {
        User.checkPassword("ten chars!");
        User.checkPassword("a".repeat(72));
        for (String bad : new String[] {null, "nine char", "a".repeat(73), "é".repeat(37)}) {
            assertThatThrownBy(() -> User.checkPassword(bad))
                    .extracting(e -> ((LedgerException) e).code()).isEqualTo(ErrorCode.PASSWORD_INVALID);
        }
    }

    @Test
    void onlyTheOwnerOwnsAnAccount() {
        UUID me = UUID.randomUUID();
        Account mine = Account.open(UUID.randomUUID(), me, "IDR", LedgerTest.NOW);
        Account system = new Account(UUID.randomUUID(), null, "IDR", 0, 0, true, AccountStatus.ACTIVE, LedgerTest.NOW);

        assertThat(mine.isOwnedBy(me)).isTrue();
        assertThat(mine.isOwnedBy(UUID.randomUUID())).isFalse();
        assertThat(system.isOwnedBy(me)).isFalse();
        assertThat(system.isOwnedBy(null)).isFalse();
    }
}
