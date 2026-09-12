package com.codewithsam.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two at once.
 *
 * The wallet holds 150. Two transfers each want 100. Exactly one of them can be
 * right, and the interesting part is what the database does about it.
 */
class ConcurrencyTest extends WalletTestBase {

    @Test
    @DisplayName("the naive read, calculate, write loses an update")
    void naivePathLosesAnUpdate() throws Exception {
        // This is the BROKEN implementation, kept in the codebase on purpose so
        // the failure can be run rather than described. Both transactions read
        // 15000, both calculate 5000, and the second write erases the first.
        runBothAtOnce(() -> transfers.unsafeTransfer(
                new TransferCommand(UUID.randomUUID().toString(), 1, 2, 10000)));

        // Both transfers were recorded as committed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class))
                .as("both attempts reported success")
                .isEqualTo(2);

        // But only ONE of them moved any money. Both read 15000 and 0, both
        // calculated 5000 and 10000, and the second write simply repeated the
        // first. One transfer vanished while telling the customer it worked.
        assertThat(balanceOf(1)).isEqualTo(5000);
        assertThat(balanceOf(2)).isEqualTo(10000);

        // This is the part that makes it hard to spot. The totals still add
        // up, so a reconciliation that only checks conservation sees nothing
        // wrong. The damage is that 200 was accepted and 100 was moved.
        assertThat(balanceOf(1) + balanceOf(2)).isEqualTo(15000);
    }

    @Test
    @DisplayName("locking the row first means the second transfer sees the truth")
    void lockedPathRefusesTheSecond() throws Exception {
        AtomicInteger refused = new AtomicInteger();

        runBothAtOnce(() -> {
            try {
                transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 10000));
            } catch (InsufficientFunds expected) {
                refused.incrementAndGet();
            }
        });

        // One succeeded, one was correctly refused.
        assertThat(refused.get()).isEqualTo(1);
        assertThat(balanceOf(1)).isEqualTo(5000);
        assertThat(balanceOf(2)).isEqualTo(10000);
        assertThat(balanceOf(1) + balanceOf(2)).isEqualTo(15000);
    }

    @Test
    @DisplayName("value is conserved even when both run at once")
    void valueIsConservedUnderConcurrency() throws Exception {
        runBothAtOnce(() -> {
            try {
                transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 10000));
            } catch (InsufficientFunds ignored) {
                // One of the two is meant to fail.
            }
        });

        assertThat(balanceOf(1) + balanceOf(2)).isEqualTo(15000);
        assertThat(ledgerBalanceOf(1) + ledgerBalanceOf(2)).isZero();
    }

    /** Start two attempts as close to simultaneously as a test can manage. */
    private void runBothAtOnce(Runnable attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    attempt.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
    }
}
