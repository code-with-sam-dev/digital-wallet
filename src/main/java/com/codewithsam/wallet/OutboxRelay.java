package com.codewithsam.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The relay.
 *
 * Reads committed outbox rows and publishes them. Deliberately separate from
 * the transfer, because the transfer's job ends at commit.
 *
 * This gives AT LEAST ONCE publication, not exactly once. The relay can publish
 * an event and then die before it records that it did, and on restart it will
 * publish the same event again. That is a property of the design, not a bug in
 * this implementation, and consumers must be idempotent because of it.
 *
 * Kafka's producer idempotence does not change this. It is enabled by default
 * only when no conflicting settings disable it, and even then it is producer
 * level protection against duplicates on retry, not end to end exactly once
 * delivery.
 *
 * There is no broker here on purpose. Adding Kafka would make the repository
 * about Kafka; the published events are collected in memory and exposed over
 * REST so the behaviour above can be watched directly.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final List<String> published = new ArrayList<>();

    public OutboxRelay(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Publish a batch, oldest first.
     *
     * FOR UPDATE SKIP LOCKED so several relay instances can share the table
     * without processing the same row twice, and without queueing behind each
     * other.
     */
    @Transactional
    public int publishBatch(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, payload FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, limit);

        for (Map<String, Object> row : rows) {
            String payload = (String) row.get("payload");

            // Publish first, record afterwards. A crash between the two means
            // the event goes out twice, which is the at least once guarantee
            // being honest about itself.
            send(payload);

            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.get("id"));
        }

        if (!rows.isEmpty()) {
            log.info("relay published {} outbox rows", rows.size());
        }
        return rows.size();
    }

    private void send(String payload) {
        published.add(payload);
    }

    /** Everything published so far, including anything published twice. */
    public List<String> published() {
        return List.copyOf(published);
    }

    /**
     * Forget what has been published.
     *
     * This exists for the tests. The list is a stand-in for a broker, and a
     * broker does not forget, so nothing in the application calls this. Without
     * it, one test's published events are still sitting here when the next test
     * counts them, and the failure looks like a bug in the relay rather than
     * what it is: shared state between tests.
     */
    public void forgetPublished() {
        published.clear();
    }

    /**
     * The background drain.
     *
     * Switched off in tests. A relay firing on a timer decides, at random, how
     * many events another test is about to find published, and a suite that
     * fails once every twenty runs is worse than no suite at all.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${wallet.relay.interval-ms:1000}")
    public void drain() {
        publishBatch(50);
    }
}
