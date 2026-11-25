package io.github.wynyga.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Shared setup: the whole application on a random port against one PostgreSQL container. All integration tests use
 * the same configuration so Spring reuses one context (and one container). Tests create their own accounts and
 * never assume an empty database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
abstract class IntegrationTest {
}
