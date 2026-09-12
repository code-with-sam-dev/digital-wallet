package com.codewithsam.wallet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real PostgreSQL, on purpose.
 *
 * Every claim this repository makes is about how PostgreSQL behaves under
 * concurrency: what Read Committed shows, what SELECT FOR UPDATE does to a
 * competing transaction, and what a serialization failure actually is. An
 * in-memory substitute would make these tests pass while teaching the opposite
 * of the truth, which is worse than having no tests at all.
 *
 * The database is the one docker compose already starts, rather than one
 * Testcontainers starts for us. That is a deliberate trade:
 *
 *   - one fewer Docker client in the stack, and therefore one fewer thing that
 *     can fail to negotiate an API version with whatever engine you happen to
 *     be running. That failure surfaces as "Could not find a valid Docker
 *     environment" while Docker is demonstrably fine, and it is a miserable
 *     first experience of somebody else's repository
 *   - the tests run against exactly the database the application runs against,
 *     with the same migrations, rather than a second one configured separately
 *
 * The cost is that `mvn test` needs the database up first. The failure message
 * below says so in one line rather than leaving you to work it out.
 *
 *     docker compose up -d postgres
 *     mvn test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class WalletTestBase {

    static final String URL = System.getProperty("wallet.test.db.url",
            System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5434/wallet"));
    static final String USER = System.getenv().getOrDefault("DB_USER", "wallet");
    static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "wallet");

    @BeforeAll
    static void requireDatabase() {
        try (var ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            // Reachable. Nothing else to do.
        } catch (Exception e) {
            fail("""

                    No PostgreSQL at %s

                    These tests run against a real database on purpose, because
                    what they assert is PostgreSQL's own concurrency behaviour.

                    Start it first:

                        docker compose up -d postgres

                    (%s)
                    """.formatted(URL, e.getMessage()));
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TransferService transfers;

    @Autowired
    protected Wallets wallets;

    @Autowired
    protected OutboxRelay relay;

    /** Two wallets, known balances, and nothing left over from the last test. */
    @BeforeEach
    void reset() {
        relay.forgetPublished();
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM idempotency");
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM transfers");
        jdbc.update("DELETE FROM wallets");
        jdbc.update("INSERT INTO wallets (id, owner, balance_minor) VALUES (1, 'Ama', 15000)");
        jdbc.update("INSERT INTO wallets (id, owner, balance_minor) VALUES (2, 'Ben', 0)");
    }

    protected long balanceOf(long walletId) {
        return jdbc.queryForObject(
                "SELECT balance_minor FROM wallets WHERE id = ?", Long.class, walletId);
    }

    /**
     * The balance as the LEDGER says it is, rather than as the projection says.
     *
     * The two must always agree. A test that only ever reads the projection
     * cannot tell you whether the projection is lying.
     */
    protected long ledgerBalanceOf(long walletId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor
                                         ELSE -amount_minor END), 0)
                FROM ledger_entries WHERE wallet_id = ?
                """, Long.class, walletId);
        return sum == null ? 0 : sum;
    }
}
