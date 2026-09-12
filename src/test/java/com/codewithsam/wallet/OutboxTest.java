package com.codewithsam.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox.
 *
 * The database commits the transfer and some other system needs to hear about
 * it. These tests pin the two properties that matter: the intent to publish
 * commits with the money, and publication is at least once rather than exactly
 * once.
 */
class OutboxTest extends WalletTestBase {

    @Test
    @DisplayName("the outbox row commits in the same transaction as the money")
    void outboxCommitsWithTheMoney() {
        var result = transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE transfer_id = ? AND published_at IS NULL",
                Integer.class, result.transferId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed transfer leaves no publish intent behind")
    void failedTransferLeavesNoOutboxRow() {
        try {
            transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 99999));
        } catch (InsufficientFunds expected) {
            // The point of the test is what is NOT in the table afterwards.
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the relay publishes committed rows and marks them")
    void relayPublishesAndMarks() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        int published = relay.publishBatch(10);

        assertThat(published).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class)).isZero();
    }

    @Test
    @DisplayName("publication is at least once, so consumers must be idempotent")
    void publicationIsAtLeastOnce() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        // Simulate the relay publishing and then dying before it could record
        // progress. On restart it publishes the same event again. This is not a
        // bug to fix here; it is the property the design actually has, and
        // pretending otherwise is how consumers end up not being idempotent.
        relay.publishBatch(10);
        jdbc.update("UPDATE outbox SET published_at = NULL");
        int again = relay.publishBatch(10);

        assertThat(again).isEqualTo(1);
        assertThat(relay.published()).hasSize(2);
    }
}
