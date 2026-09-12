package com.codewithsam.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moving money.
 *
 * The whole design is one sentence: every financial change for one transfer
 * commits together, or none of it does.
 *
 * Inside that boundary, in order:
 *
 *   lock the wallet rows, in a deterministic order
 *   check the available funds against the row we now hold
 *   create the transfer
 *   write the balanced ledger entries
 *   update the balance projection
 *   persist the idempotency result
 *   write the outbox record
 *
 * Then commit, once. If the process dies anywhere above, none of it becomes
 * committed state, so a crash cannot leave a sender debited and a receiver
 * un-credited.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final JdbcTemplate jdbc;
    private final Wallets wallets;
    private final WalletMetrics metrics;

    /**
     * An explicit transaction, not an annotation.
     *
     * `@Transactional` is applied by a proxy, so it does nothing when a method
     * on this class calls another method on this class: the call never leaves
     * the object and never passes through the proxy. The first version of this
     * file did exactly that, and the result was a transfer that ran with NO
     * transaction at all. Every test about atomicity passed anyway, because
     * each one ran a single statement at a time. Only the concurrency test
     * noticed, and only because the row lock silently did nothing.
     *
     * A TransactionTemplate cannot fail that way. The boundary is a line of
     * code you can see rather than an annotation you have to reason about.
     */
    private final TransactionTemplate tx;

    public TransferService(JdbcTemplate jdbc, Wallets wallets, WalletMetrics metrics,
                           PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * The correct path.
     *
     * @throws InsufficientFunds     the sender cannot cover it
     * @throws IdempotencyConflict   the key was used for a different request
     */
    public TransferResult transfer(TransferCommand command) {
        Instant started = Instant.now();
        try {
            TransferResult result = attempt(command);
            metrics.recordOutcome(result.replayed() ? "replayed" : "committed");
            return result;
        } catch (InsufficientFunds e) {
            metrics.recordOutcome("insufficient_funds");
            throw e;
        } catch (IdempotencyConflict e) {
            metrics.recordOutcome("conflict");
            throw e;
        } catch (RuntimeException e) {
            metrics.recordOutcome("failed");
            throw e;
        } finally {
            metrics.recordDuration(Duration.between(started, Instant.now()));
        }
    }

    private TransferResult attempt(TransferCommand command) {
        return tx.execute(status -> attemptInTransaction(command));
    }

    /** Everything here commits together, or none of it does. */
    private TransferResult attemptInTransaction(TransferCommand command) {
        // A replay is a lookup. Checking first keeps the common retry cheap,
        // and the unique constraint below is what makes it correct when two
        // retries arrive at once.
        TransferResult replay = findReplay(command);
        if (replay != null) {
            return replay;
        }

        Instant lockStart = Instant.now();
        wallets.lockInIdOrder(command.fromWallet(), command.toWallet());
        metrics.recordLockWait(Duration.between(lockStart, Instant.now()));

        // Read AFTER the lock. Reading before it is the whole bug: the value
        // would be a snapshot that another transaction may already have moved on
        // from, and the decision would be made against a balance that no longer
        // exists.
        long available = wallets.balanceOf(command.fromWallet());
        if (available < command.amountMinor()) {
            throw new InsufficientFunds(command.fromWallet(), available, command.amountMinor());
        }

        UUID transferId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers (id, from_wallet, to_wallet, amount_minor, status)
                VALUES (?, ?, ?, ?, 'COMMITTED')
                """, transferId, command.fromWallet(), command.toWallet(), command.amountMinor());

        // Double entry. Two rows, equal and opposite, written together. The
        // ledger is the financial history; the balance below is a result of it.
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, direction, amount_minor)
                VALUES (?, ?, 'DEBIT', ?)
                """, transferId, command.fromWallet(), command.amountMinor());
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, direction, amount_minor)
                VALUES (?, ?, 'CREDIT', ?)
                """, transferId, command.toWallet(), command.amountMinor());

        // Relative, never absolute. See Wallets.applyDelta.
        wallets.applyDelta(command.fromWallet(), -command.amountMinor());
        wallets.applyDelta(command.toWallet(), command.amountMinor());

        try {
            jdbc.update("""
                    INSERT INTO idempotency (key, request_hash, transfer_id) VALUES (?, ?, ?)
                    """, command.idempotencyKey(), command.requestHash(), transferId);
        } catch (DuplicateKeyException race) {
            // Two identical retries arrived at the same moment. The unique
            // constraint is the arbiter; the loser rolls back and reads the
            // winner's result rather than moving the money a second time.
            throw new ConcurrentReplay();
        }

        // The publish intent, in the SAME transaction as the money. This is the
        // outbox: it closes the gap where money commits but nothing durable
        // records that an event was owed.
        jdbc.update("""
                INSERT INTO outbox (transfer_id, event_type, payload)
                VALUES (?, 'TransferCommitted', ?)
                """, transferId, payload(transferId, command));

        log.info("transfer committed transfer_id={} from={} to={} minor={}",
                transferId, command.fromWallet(), command.toWallet(), command.amountMinor());

        return new TransferResult(transferId, "COMMITTED", false);
    }

    private TransferResult findReplay(TransferCommand command) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT transfer_id, request_hash FROM idempotency WHERE key = ?",
                command.idempotencyKey());
        if (rows.isEmpty()) {
            return null;
        }

        String storedHash = (String) rows.get(0).get("request_hash");
        if (!storedHash.equals(command.requestHash())) {
            throw new IdempotencyConflict(command.idempotencyKey());
        }
        return new TransferResult((UUID) rows.get(0).get("transfer_id"), "COMMITTED", true);
    }

    /**
     * The BROKEN path, kept on purpose.
     *
     * Read the balance, calculate a new absolute value in application code,
     * write it later. No lock, so both transactions read the same starting
     * balance and the second write erases the first.
     *
     * This exists so the lost update can be RUN rather than described. It is
     * never reachable from the normal transfer endpoint.
     */
    public TransferResult unsafeTransfer(TransferCommand command) {
        return tx.execute(status -> unsafeInTransaction(command));
    }

    private TransferResult unsafeInTransaction(TransferCommand command) {
        // No lock. This is the only thing that differs from the correct path,
        // besides writing an absolute balance instead of a relative delta.
        // Everything else, including the ledger entries, is identical, so the
        // demonstration isolates one variable rather than changing five things
        // and claiming the difference proves something.
        long from = wallets.balanceOf(command.fromWallet());
        long to = wallets.balanceOf(command.toWallet());

        // The window. Another transaction reads the same two values here.
        sleepBriefly();

        long newFrom = from - command.amountMinor();
        long newTo = to + command.amountMinor();

        UUID transferId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers (id, from_wallet, to_wallet, amount_minor, status)
                VALUES (?, ?, ?, ?, 'COMMITTED')
                """, transferId, command.fromWallet(), command.toWallet(), command.amountMinor());

        // The ledger still records both movements, which is what makes the
        // damage visible: afterwards the entries say one amount moved and the
        // balance says another. "Which entries produced this number, and can I
        // prove it" stops being a rhetorical question.
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, direction, amount_minor)
                VALUES (?, ?, 'DEBIT', ?)
                """, transferId, command.fromWallet(), command.amountMinor());
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, direction, amount_minor)
                VALUES (?, ?, 'CREDIT', ?)
                """, transferId, command.toWallet(), command.amountMinor());

        // Absolute, calculated in application code from a value that may
        // already be stale. This is the line that loses the update.
        wallets.overwriteBalance(command.fromWallet(), newFrom);
        wallets.overwriteBalance(command.toWallet(), newTo);

        return new TransferResult(transferId, "COMMITTED", false);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String payload(UUID transferId, TransferCommand command) {
        return """
               {"transferId":"%s","from":%d,"to":%d,"amountMinor":%d}
               """.formatted(transferId, command.fromWallet(), command.toWallet(), command.amountMinor()).trim();
    }

    /** Two identical retries raced; the loser reads the winner's result. */
    static class ConcurrentReplay extends RuntimeException {}
}
