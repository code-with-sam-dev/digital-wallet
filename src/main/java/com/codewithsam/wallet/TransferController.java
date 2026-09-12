package com.codewithsam.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The demonstration surface.
 *
 * REST rather than a UI, deliberately. A UI would be a second thing to maintain
 * and would hide the behaviour it exists to show; curl output cannot hide
 * anything.
 *
 *   POST /transfers                  the correct path
 *   POST /demo/lost-update           watch two transfers lose one
 *   POST /demo/safe-race             watch the same race, done properly
 *   GET  /wallets                    balances, and what the ledger says
 *   GET  /ledger/{transferId}        the entries behind one transfer
 *   GET  /outbox                     the publish backlog
 *   GET  /events                     what the relay has published
 */
@RestController
public class TransferController {

    private final TransferService transfers;
    private final OutboxRelay relay;
    private final JdbcTemplate jdbc;

    public TransferController(TransferService transfers, OutboxRelay relay, JdbcTemplate jdbc) {
        this.transfers = transfers;
        this.relay = relay;
        this.jdbc = jdbc;
    }

    public record TransferRequest(long from, long to, long amountMinor) {}

    @PostMapping("/transfers")
    public ResponseEntity<?> transfer(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody TransferRequest request) {
        var result = transfers.transfer(
                new TransferCommand(key, request.from(), request.to(), request.amountMinor()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    /**
     * Reproduce the lost update.
     *
     * Two transfers of the same amount, started together, through the path that
     * reads and then writes an absolute value. Both report success and the
     * money does not add up afterwards.
     */
    @PostMapping("/demo/lost-update")
    public Map<String, Object> lostUpdate(@RequestBody TransferRequest request) throws Exception {
        return race(request, false);
    }

    /** The same race, through the locking path. One succeeds, one is refused. */
    @PostMapping("/demo/safe-race")
    public Map<String, Object> safeRace(@RequestBody TransferRequest request) throws Exception {
        return race(request, true);
    }

    private Map<String, Object> race(TransferRequest request, boolean safe) throws Exception {
        long before = balance(request.from()) + balance(request.to());

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(2);
        var outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    var command = new TransferCommand(
                            UUID.randomUUID().toString(), request.from(), request.to(), request.amountMinor());
                    if (safe) {
                        transfers.transfer(command);
                    } else {
                        transfers.unsafeTransfer(command);
                    }
                    outcomes.add("committed");
                } catch (InsufficientFunds e) {
                    outcomes.add("refused: " + e.getMessage());
                } catch (Exception e) {
                    outcomes.add("failed: " + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        go.countDown();
        done.await(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdownNow();

        long after = balance(request.from()) + balance(request.to());

        // The interesting number is not whether the totals match. Both paths
        // conserve value here, which is exactly why a lost update is so hard to
        // catch: a reconciliation that only checks conservation sees nothing
        // wrong at all.
        //
        // The damage is the gap between what was ACCEPTED and what actually
        // MOVED. Two transfers can report success while only one of them
        // happened.
        long accepted = outcomes.stream().filter(o -> o.equals("committed")).count();
        long ledgerSays = creditedTo(request.to());
        long balanceSays = balance(request.to());

        java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("path", safe ? "SELECT FOR UPDATE" : "read, calculate, write");
        report.put("outcomes", outcomes);
        report.put("transfersAccepted", accepted);
        report.put("ledgerSaysReceived", ledgerSays);
        report.put("balanceSaysReceived", balanceSays);
        report.put("totalBefore", before);
        report.put("totalAfter", after);
        report.put("valueConserved", before == after);
        report.put("verdict", ledgerSays == balanceSays
                ? "The ledger and the balance agree."
                : ("The ledger says %d arrived. The balance says %d. "
                   + "One update overwrote the other, and the balance is now a number "
                   + "no set of entries can explain.").formatted(ledgerSays, balanceSays));
        return report;
    }

    @GetMapping("/wallets")
    public List<Map<String, Object>> wallets() {
        // Both numbers, side by side. A balance you cannot check against the
        // entries that produced it is a number you are choosing to trust.
        return jdbc.queryForList("""
                SELECT w.id, w.owner, w.balance_minor AS projection_minor,
                       COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor
                                         ELSE -l.amount_minor END), 0) AS ledger_movement_minor
                FROM wallets w
                LEFT JOIN ledger_entries l ON l.wallet_id = w.id
                GROUP BY w.id, w.owner, w.balance_minor
                ORDER BY w.id
                """);
    }

    @GetMapping("/ledger/{transferId}")
    public List<Map<String, Object>> ledger(@PathVariable UUID transferId) {
        return jdbc.queryForList("""
                SELECT wallet_id, direction, amount_minor, created_at
                FROM ledger_entries WHERE transfer_id = ? ORDER BY id
                """, transferId);
    }

    @GetMapping("/outbox")
    public List<Map<String, Object>> outbox() {
        return jdbc.queryForList("""
                SELECT id, transfer_id, event_type, published_at, created_at
                FROM outbox ORDER BY created_at
                """);
    }

    @PostMapping("/outbox/drain")
    public Map<String, Object> drain() {
        return Map.of("published", relay.publishBatch(50));
    }

    @GetMapping("/events")
    public List<String> events() {
        return relay.published();
    }

    @ExceptionHandler(InsufficientFunds.class)
    public ResponseEntity<Map<String, String>> insufficient(InsufficientFunds e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflict.class)
    public ResponseEntity<Map<String, String>> conflict(IdempotencyConflict e) {
        // 409, because the request conflicts with a request already recorded
        // under that key. Not 200 with the old result, which would tell the
        // caller something untrue.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /**
     * What the LEDGER says arrived.
     *
     * Read from the entries rather than the balance, because the balance is
     * the thing under suspicion in this demonstration.
     */
    private long creditedTo(long toWallet) {
        Long moved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries "
                        + "WHERE wallet_id = ? AND direction = 'CREDIT'",
                Long.class, toWallet);
        return moved == null ? 0 : moved;
    }

    private long balance(long walletId) {
        Long b = jdbc.queryForObject("SELECT balance_minor FROM wallets WHERE id = ?", Long.class, walletId);
        return b == null ? 0 : b;
    }
}
