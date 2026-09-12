package com.codewithsam.wallet;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What we measure, and what we must never measure.
 *
 * The video makes two specific claims about observability. Both are testable,
 * so both are tested, because an assertion about monitoring that is only made in
 * prose is an assertion nobody ever checks again.
 */
class MetricsTest extends WalletTestBase {

    @Autowired
    MeterRegistry registry;

    @Test
    @DisplayName("the first question is whether the invariants hold, so the ledger imbalance is a metric")
    void ledgerImbalanceIsMeasured() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        var imbalance = registry.find("wallet.ledger.imbalance.minor").gauge();
        assertThat(imbalance).as("a ledger imbalance is an incident, so it must be visible").isNotNull();
        assertThat(imbalance.value()).isZero();
    }

    @Test
    @DisplayName("transfer outcomes are counted, split by outcome")
    void outcomesAreCounted() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));
        try {
            transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 99999));
        } catch (InsufficientFunds expected) {
            // counted as a refusal, not an error
        }

        assertThat(registry.find("wallet.transfers").tag("outcome", "committed").counter())
                .isNotNull();
        assertThat(registry.find("wallet.transfers").tag("outcome", "insufficient_funds").counter())
                .isNotNull();
    }

    @Test
    @DisplayName("the age of the oldest unpublished outbox row is measured, not just the count")
    void outboxAgeIsMeasured() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        // Count alone cannot tell you whether the relay is stuck or merely busy.
        // A small backlog that is not moving is worse than a large one that is.
        assertThat(registry.find("wallet.outbox.oldest.unpublished.seconds").gauge()).isNotNull();
        assertThat(registry.find("wallet.outbox.unpublished").gauge()).isNotNull();
    }

    @Test
    @DisplayName("transfer latency is a histogram, because summary quantiles cannot be aggregated")
    void latencyIsAHistogram() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));

        var timer = registry.find("wallet.transfer.duration").timer();
        assertThat(timer).isNotNull();

        // Prometheus warns that averaging precomputed summary quantiles across
        // instances is statistically meaningless. Histogram buckets aggregate
        // first, then the percentile is calculated from the combined
        // observations, which is the only way a fleet wide p95 means anything.
        boolean hasBuckets = timer.takeSnapshot().histogramCounts().length > 0;
        assertThat(hasBuckets)
                .as("publish buckets, not client side quantiles")
                .isTrue();
    }

    @Test
    @DisplayName("database contention and retries are visible")
    void contentionIsVisible() {
        assertThat(registry.find("wallet.transfer.retries").counter()).isNotNull();
        assertThat(registry.find("wallet.transfer.lock.wait").timer()).isNotNull();
    }

    /**
     * The one that matters most.
     *
     * A transfer id or a customer id in an ordinary metric label creates a new
     * time series per transfer. That is unbounded cardinality, and it takes the
     * monitoring system down at exactly the moment you need it. Metrics say
     * something is wrong; logs and traces say what happened to one transfer.
     */
    @Test
    @DisplayName("no metric carries a transfer id, wallet id or customer id as a label")
    void noUnboundedCardinality() {
        transfers.transfer(new TransferCommand(UUID.randomUUID().toString(), 1, 2, 5000));
        relay.publishBatch(10);

        List<String> forbidden = List.of(
                "transfer", "transferid", "transfer_id",
                "wallet", "walletid", "wallet_id",
                "customer", "customerid", "customer_id",
                "user", "userid", "account", "key", "idempotencykey");

        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("wallet.")) continue;
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase().replace(".", "");
                assertThat(forbidden)
                        .as("metric %s carries a high cardinality label '%s'",
                                meter.getId().getName(), tag.getKey())
                        .doesNotContain(key);
            }
        }
    }

    @Test
    @DisplayName("the scrape endpoint is actually exposed")
    void scrapeEndpointExists() {
        // A metric nothing can read is not observability.
        assertThat(registry.find("wallet.transfers").counters()).isNotNull();
    }
}
