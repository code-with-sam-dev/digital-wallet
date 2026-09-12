package com.codewithsam.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The five invariants, as tests.
 *
 * These are the things the video says must always remain true. They are written
 * first and separately from everything else because they are the actual
 * requirements; the schema and the service exist to satisfy them.
 */
class InvariantsTest extends WalletTestBase {

    @Test
    @DisplayName("a transfer does not create value")
    void doesNotCreateValue() {
        long before = balanceOf(1) + balanceOf(2);
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));
        assertThat(balanceOf(1) + balanceOf(2)).isEqualTo(before);
    }

    @Test
    @DisplayName("a transfer does not destroy value")
    void doesNotDestroyValue() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));
        assertThat(balanceOf(1)).isEqualTo(10000);
        assertThat(balanceOf(2)).isEqualTo(5000);
    }

    @Test
    @DisplayName("the ledger always balances: total debits equal total credits")
    void ledgerBalances() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 2, 1, 1500));

        Long debits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor),0) FROM ledger_entries WHERE direction = 'DEBIT'",
                Long.class);
        Long credits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor),0) FROM ledger_entries WHERE direction = 'CREDIT'",
                Long.class);

        assertThat(debits).isEqualTo(credits);
    }

    @Test
    @DisplayName("the stored balance always agrees with the ledger that produced it")
    void projectionAgreesWithLedger() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 2500));

        // Wallet 1 started at 15000 outside the ledger, so compare the MOVEMENT.
        assertThat(ledgerBalanceOf(1)).isEqualTo(balanceOf(1) - 15000);
        assertThat(ledgerBalanceOf(2)).isEqualTo(balanceOf(2));
    }

    @Test
    @DisplayName("a transfer larger than the balance is refused")
    void refusesInsufficientFunds() {
        assertThatThrownBy(() ->
                transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 99999)))
                .isInstanceOf(InsufficientFunds.class);

        assertThat(balanceOf(1)).isEqualTo(15000);
        assertThat(balanceOf(2)).isZero();
    }

    @Test
    @DisplayName("reporting success means durable records exist to explain it")
    void successLeavesRecords() {
        var result = transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE id = ?", Integer.class, result.transferId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Integer.class, result.transferId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE transfer_id = ?", Integer.class, result.transferId()))
                .isEqualTo(1);
    }
}
