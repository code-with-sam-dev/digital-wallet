package com.codewithsam.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry.
 *
 * A payment succeeds and the response is lost. The client cannot tell the
 * difference between that and a payment that never happened, so it sends the
 * request again. Everything here is about making that second request safe.
 */
class IdempotencyTest extends WalletTestBase {

    @Test
    @DisplayName("the same key twice moves the money once")
    void sameKeyMovesMoneyOnce() {
        var command = new TransferCommand("key-abc", 1, 2, 5000);

        var first = transfers.transfer(command);
        var second = transfers.transfer(command);

        assertThat(second.transferId()).isEqualTo(first.transferId());
        assertThat(balanceOf(1)).isEqualTo(10000);
        assertThat(balanceOf(2)).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the replay is a lookup, so it writes no new ledger entries")
    void replayWritesNothing() {
        var command = new TransferCommand("key-abc", 1, 2, 5000);
        transfers.transfer(command);
        transfers.transfer(command);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with different data is a client error, not a duplicate")
    void sameKeyDifferentDataIsRejected() {
        transfers.transfer(new TransferCommand("key-abc", 1, 2, 5000));

        // Same key, different amount. Returning the first result here would be
        // worse than failing: the client would believe a transfer it never
        // asked for had been carried out.
        assertThatThrownBy(() -> transfers.transfer(new TransferCommand("key-abc", 1, 2, 9999)))
                .isInstanceOf(IdempotencyConflict.class);

        assertThat(balanceOf(1)).isEqualTo(10000);
    }

    @Test
    @DisplayName("the idempotency record commits with the money, not separately")
    void recordCommitsWithTheMoney() {
        // A transfer that fails must leave NO idempotency record behind, or the
        // retry would find a key pointing at a transfer that does not exist.
        assertThatThrownBy(() -> transfers.transfer(new TransferCommand("key-broke", 1, 2, 99999)))
                .isInstanceOf(InsufficientFunds.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency WHERE key = 'key-broke'", Integer.class)).isZero();
    }
}
