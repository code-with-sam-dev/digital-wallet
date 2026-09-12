package com.codewithsam.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A digital wallet transfer, built so the failures are runnable.
 *
 * Everything the companion video claims is in here as code you can execute:
 * the idempotent retry, the lost update, the fix, one atomic transaction, the
 * transactional outbox, and the monitoring that tells you whether the money
 * still adds up.
 *
 *     docker compose up --build
 *
 * There is no UI. Demonstrations are REST endpoints, because a UI would be a
 * second thing to maintain and would hide the very behaviour it is meant to
 * show.
 */
@SpringBootApplication
public class WalletApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
