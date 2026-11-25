package io.github.wynyga.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Shared setup: the whole application on a random port against one PostgreSQL container. All integration tests use
 * the same configuration so Spring reuses one context (and one container). Tests create their own users and
 * accounts and never assume an empty database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.admin.username=" + IntegrationTest.ADMIN,
        "ledger.admin.password=" + IntegrationTest.ADMIN_PASSWORD})
@Import(TestcontainersConfiguration.class)
abstract class IntegrationTest {
    static final String ADMIN = "admin";
    static final String ADMIN_PASSWORD = "admin-password-for-tests";
    static final String PASSWORD = "correct horse battery";
}
