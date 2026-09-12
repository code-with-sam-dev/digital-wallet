package com.codewithsam.wallet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Wallet reads and writes.
 *
 * The two lock methods are the whole argument of the concurrency section, so
 * they are named for what they do to a competing transaction rather than for
 * the SQL they run.
 */
@Repository
public class Wallets {

    private final JdbcTemplate jdbc;

    public Wallets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A plain read. Sees a snapshot from the start of this statement. */
    public long balanceOf(long walletId) {
        Long balance = jdbc.queryForObject(
                "SELECT balance_minor FROM wallets WHERE id = ?", Long.class, walletId);
        if (balance == null) {
            throw new IllegalArgumentException("no wallet " + walletId);
        }
        return balance;
    }

    /**
     * Lock the rows this transfer touches, in a deterministic order.
     *
     * The ORDER BY is not cosmetic. Two concurrent transfers that lock the same
     * two wallets in opposite orders will deadlock waiting on each other.
     * Sorting by id means every transaction in the system takes the same two
     * locks in the same sequence, so that deadlock cannot form.
     *
     * PostgreSQL would detect the deadlock and abort one transaction, but an
     * abort you designed out is better than an abort you have to retry.
     */
    public void lockInIdOrder(long a, long b) {
        List<Long> ids = a < b ? List.of(a, b) : List.of(b, a);
        jdbc.query(
                "SELECT id FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                rs -> {},
                ids.get(0), ids.get(1));
    }

    /**
     * Apply a movement to the stored balance projection.
     *
     * Relative, never absolute. `balance = balance + delta` is resolved by the
     * database against the row it currently holds; `balance = <number I worked
     * out earlier>` is resolved against a value that may already be stale. That
     * single difference is the lost update.
     */
    public void applyDelta(long walletId, long deltaMinor) {
        jdbc.update("UPDATE wallets SET balance_minor = balance_minor + ? WHERE id = ?",
                deltaMinor, walletId);
    }

    /**
     * Write an absolute balance, calculated in application code.
     *
     * Kept deliberately, and used only by the broken demonstration path. This
     * is the line that loses money, and the repository is more honest for
     * containing it than for describing it.
     */
    public void overwriteBalance(long walletId, long absoluteMinor) {
        jdbc.update("UPDATE wallets SET balance_minor = ? WHERE id = ?", absoluteMinor, walletId);
    }
}
