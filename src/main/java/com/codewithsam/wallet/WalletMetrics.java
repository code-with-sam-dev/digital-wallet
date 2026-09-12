package com.codewithsam.wallet;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What this system measures.
 *
 * The first observability question for a payment system is not CPU usage. It is
 * whether the financial invariants still hold. A ledger imbalance is an
 * incident, and it should be the loudest thing on the dashboard.
 *
 * Two rules are enforced by tests rather than by discipline:
 *
 *   1. Latency is a HISTOGRAM, never a client side summary. Prometheus warns
 *      that averaging precomputed summary quantiles across instances is
 *      statistically meaningless. Buckets aggregate first, then the percentile
 *      is calculated from the combined observations.
 *
 *   2. No transfer id, wallet id or customer id ever becomes a label. That is
 *      unbounded cardinality, and it takes the monitoring system down at
 *      exactly the moment you need it. Metrics tell you something is wrong.
 *      Logs and traces tell you what happened to one transfer.
 */
@Component
public class WalletMetrics {

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    private final Timer duration;
    private final Timer lockWait;
    private final Counter retries;

    /** Cached so the gauges can be read without touching the database per scrape. */
    private final AtomicLong imbalance = new AtomicLong();
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong oldestUnpublishedSeconds = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();

    public WalletMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;

        this.duration = Timer.builder("wallet.transfer.duration")
                .description("End to end time to commit a transfer")
                // Buckets, not quantiles. See the class comment.
                //
                // The boundaries are declared rather than left to
                // publishPercentileHistogram alone, because that flag is a hint
                // some registries ignore, and a histogram nobody exports is
                // indistinguishable from the summary we are arguing against.
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(5), Duration.ofMillis(25), Duration.ofMillis(100),
                        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofSeconds(5))
                .register(registry);

        this.lockWait = Timer.builder("wallet.transfer.lock.wait")
                .description("Time spent waiting to lock the wallet rows, which is contention")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(50),
                        Duration.ofMillis(200), Duration.ofSeconds(1))
                .register(registry);

        this.retries = Counter.builder("wallet.transfer.retries")
                .description("Transactions retried after a serialization failure or deadlock")
                .register(registry);

        registry.gauge("wallet.ledger.imbalance.minor", imbalance, AtomicLong::doubleValue);
        registry.gauge("wallet.outbox.unpublished", unpublished, AtomicLong::doubleValue);
        registry.gauge("wallet.outbox.oldest.unpublished.seconds",
                oldestUnpublishedSeconds, AtomicLong::doubleValue);
        registry.gauge("wallet.transfers.stuck", stuck, AtomicLong::doubleValue);

        // Registered up front so a dashboard is never missing a series just
        // because nothing has failed yet. A panel that appears only during an
        // incident is a panel nobody has ever looked at.
        for (String outcome : new String[]{"committed", "replayed", "insufficient_funds", "conflict", "failed"}) {
            outcomeCounter(outcome);
        }

        refresh();
    }

    private Counter outcomeCounter(String outcome) {
        return Counter.builder("wallet.transfers")
                .description("Transfer attempts by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    public void recordOutcome(String outcome) {
        outcomeCounter(outcome).increment();
    }

    public void recordDuration(Duration taken) {
        duration.record(taken);
    }

    public void recordLockWait(Duration taken) {
        lockWait.record(taken);
    }

    public void recordRetry() {
        retries.increment();
    }

    /**
     * Recompute the invariant gauges.
     *
     * Scheduled rather than computed per scrape, so a slow query cannot make
     * the metrics endpoint itself the thing that falls over.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${wallet.metrics.refresh-ms:5000}")
    public void refresh() {
        // The invariant: every ledger entry belongs to a balanced pair, so the
        // signed sum across all entries must be exactly zero. Any other number
        // means value was created or destroyed.
        Long signedSum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor
                                         ELSE -amount_minor END), 0)
                FROM ledger_entries
                """, Long.class);
        imbalance.set(signedSum == null ? 0 : signedSum);

        Long backlog = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
        unpublished.set(backlog == null ? 0 : backlog);

        // Age, not count. A small backlog that is not moving is a worse signal
        // than a large one that is draining.
        Long oldest = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)::bigint
                FROM outbox WHERE published_at IS NULL
                """, Long.class);
        oldestUnpublishedSeconds.set(oldest == null ? 0 : oldest);

        Long pending = jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE status NOT IN ('COMMITTED','FAILED')", Long.class);
        stuck.set(pending == null ? 0 : pending);
    }
}
