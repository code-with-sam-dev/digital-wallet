# Digital Wallet

A wallet to wallet transfer, built so the failures are **runnable** rather than
described.

Companion code for [Design a Digital Wallet: The $100 Transfer That Disappears](https://youtu.be/fdrbDnkAruU)
on [Code with Sam](https://www.youtube.com/@CodewithSam-Dev).

Everything the video claims is in here as code you can execute: the idempotent
retry, the lost update, the fix, one atomic transaction, the transactional
outbox, and the monitoring that tells you whether the money still adds up.

## Run it

```bash
docker compose up --build
```

| | |
|---|---|
| wallet | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 (admin / admin) |
| PostgreSQL | localhost:5434 |

Ports are deliberately off the defaults. Anyone doing this kind of work already
has a PostgreSQL on 5432 and something on 3000, and a repository that refuses to
start because of that is a repository nobody runs twice.

There is no UI. Demonstrations are REST endpoints, because a UI would be a
second thing to maintain and would hide the behaviour it exists to show.

## The four attacks

### 1. The same request arrives twice

```bash
curl -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: abc' \
  -d '{"from":1,"to":2,"amountMinor":5000}'

# the retry: same key, same body
curl -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: abc' \
  -d '{"from":1,"to":2,"amountMinor":5000}'
```

The second call returns the **same transfer id** with `"replayed": true`. No
second money movement.

Send the same key with a different amount and you get **409**, not a replay.
Returning the first result there would tell the caller that a transfer it never
asked for had been carried out.

### 2. Two transfers spend the same balance

```bash
curl -X POST localhost:8080/demo/lost-update \
  -H 'Content-Type: application/json' -d '{"from":1,"to":2,"amountMinor":10000}'
```

```json
{
  "transfersAccepted": 2,
  "ledgerSaysReceived": 20000,
  "balanceSaysReceived": 10000,
  "valueConserved": true,
  "verdict": "The ledger says 20000 arrived. The balance says 10000. One update
              overwrote the other, and the balance is now a number no set of
              entries can explain."
}
```

Look at `valueConserved`. It is **true**. The totals still add up, which is
exactly why a lost update is so hard to catch: a reconciliation that only checks
conservation sees nothing wrong. The damage is that the ledger and the balance
now disagree.

Then the same race, done properly:

```bash
curl -X POST localhost:8080/demo/safe-race \
  -H 'Content-Type: application/json' -d '{"from":1,"to":2,"amountMinor":10000}'
```

One commits, one is refused, and the ledger and the balance agree.

The only differences between the two paths are `SELECT ... FOR UPDATE` before
the decision, and writing a **relative delta** instead of an absolute value
calculated in application code. One variable at a time, or the demonstration
proves nothing.

### 3. A crash between the two movements

Every financial change for one transfer commits together or not at all: the
transfer, the balanced ledger entries, the balance projection, the idempotency
result and the outbox record, inside one transaction.

`InvariantsTest` and `OutboxTest` pin it. A refused transfer leaves no
idempotency record and no outbox row behind.

### 4. Proving what happened, months later

`ledger_entries` is append only. There is no UPDATE or DELETE path to it
anywhere in the application, which is what makes it a history rather than a
cache. `GET /wallets` shows the stored projection and the ledger movement side
by side, because a balance you cannot check against the entries that produced it
is a number you are choosing to trust.

## The outbox

```bash
curl localhost:8080/outbox      # the backlog
curl -X POST localhost:8080/outbox/drain
curl localhost:8080/events      # what the relay published
```

The outbox row commits in the same transaction as the money. A separate relay
publishes committed rows afterwards.

This is **at least once**, not exactly once. The relay can publish and then die
before recording that it did, and on restart it publishes the same event again.
`OutboxTest.publicationIsAtLeastOnce` asserts that behaviour rather than
pretending otherwise, because consumers have to be idempotent and they will not
be if the documentation claims they need not be.

There is no broker here on purpose. Adding Kafka would make the repository about
Kafka; published events are collected in memory and exposed over REST so the
behaviour above can be watched directly.

## Observability

`ops/` contains the whole stack: Prometheus with alert rules, and a provisioned
Grafana dashboard.

The first question is not CPU usage. It is whether the financial invariants
still hold, so the top row of the dashboard and the first alert rule are both
about the ledger balancing.

| Metric | Why it is there |
|---|---|
| `wallet_ledger_imbalance_minor` | Signed sum of all entries. Anything but zero means value was created or destroyed |
| `wallet_outbox_oldest_unpublished_seconds` | **Age**, not count. A small backlog that is not moving is the worse signal |
| `wallet_transfers_total{outcome}` | Refusals counted separately from errors. A system refusing correctly is healthy |
| `wallet_transfer_duration_seconds_bucket` | A **histogram**. See below |
| `wallet_transfer_lock_wait_seconds` | Contention, measured rather than guessed |
| `wallet_transfers_stuck` | Non terminal for minutes means a lock, a dead worker, or a retry loop |

Two rules are enforced by tests rather than by discipline:

**Latency is a histogram, never a client side summary.** Prometheus warns that
averaging precomputed summary quantiles across instances is statistically
meaningless. Buckets aggregate first, then the percentile is calculated from the
combined observations.

**No transfer id, wallet id or customer id is ever a label.** That is unbounded
cardinality, and it takes the monitoring system down at exactly the moment you
need it. `MetricsTest.noUnboundedCardinality` walks every registered meter and
fails if one appears. Metrics tell you something is wrong; logs and traces tell
you what happened to one transfer.

## Tests

```bash
docker compose up -d postgres
mvn test
```

24 tests, against a real PostgreSQL. Not an in-memory substitute: everything
asserted here is PostgreSQL's own concurrency behaviour, and a substitute would
make the tests pass while teaching the opposite of the truth.

The database is the one `docker compose` already starts rather than one
Testcontainers starts for us. One fewer Docker client in the stack is one fewer
thing that can fail to negotiate an API version with whatever engine you happen
to be running, and that failure surfaces as "Could not find a valid Docker
environment" while Docker is demonstrably fine. If the database is not up, the
tests say so in one line instead of leaving you to work it out.

| Suite | What it holds |
|---|---|
| `InvariantsTest` | The five invariants: value not created, not destroyed, ledger balances, projection agrees, success leaves records |
| `IdempotencyTest` | Retry safety, conflict detection, and that a failed transfer leaves no key behind |
| `ConcurrencyTest` | The lost update happening, and then not happening |
| `OutboxTest` | Commit coupling, and at least once publication |
| `MetricsTest` | Histogram not summary, and no unbounded cardinality |

## A bug worth keeping

The first version put `@Transactional` on a method that another method in the
same class called directly. Spring applies it with a proxy, so a call that never
leaves the object never passes through the proxy, and the transfer ran with **no
transaction at all**.

Every test about atomicity passed anyway, because each ran one statement at a
time. Only the concurrency test noticed, and only because the row lock silently
did nothing.

The fix is an explicit `TransactionTemplate`: a boundary you can see rather than
an annotation you have to reason about. It is written up here because the bug is
more instructive than the fix.

## Licence

MIT. Use it, teach from it, put it in your own interview preparation.
