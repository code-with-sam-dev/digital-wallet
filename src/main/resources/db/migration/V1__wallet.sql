-- The financial schema.
--
-- Shapes worth noticing, because each one is an argument the video makes:
--
--   wallets            the balance is a PROJECTION, not the source of truth
--   ledger_entries     the source of truth, append only, debits equal credits
--   idempotency        the retry guard, written in the SAME transaction
--   outbox             the publish intent, also written in the SAME transaction
--
-- Money is NUMERIC, never a floating point type. A double cannot represent
-- 0.10 exactly, and a payment system that rounds is a payment system that
-- loses arguments with accountants.

CREATE TABLE wallets (
    id              BIGINT PRIMARY KEY,
    owner           TEXT           NOT NULL,
    -- Derived from ledger_entries. Kept for fast reads, never authoritative.
    balance_minor   BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    id              UUID PRIMARY KEY,
    from_wallet     BIGINT         NOT NULL REFERENCES wallets(id),
    to_wallet       BIGINT         NOT NULL REFERENCES wallets(id),
    amount_minor    BIGINT         NOT NULL CHECK (amount_minor > 0),
    status          TEXT           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Append only. There is no UPDATE or DELETE path to this table anywhere in the
-- application, which is what makes it a history rather than a cache.
CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    transfer_id     UUID           NOT NULL REFERENCES transfers(id),
    wallet_id       BIGINT         NOT NULL REFERENCES wallets(id),
    -- 'DEBIT' or 'CREDIT'. Accounting directions, not minus and plus.
    direction       TEXT           NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT         NOT NULL CHECK (amount_minor > 0),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX ledger_by_wallet ON ledger_entries (wallet_id);
CREATE INDEX ledger_by_transfer ON ledger_entries (transfer_id);

-- The key is unique, which is what makes a retry a lookup rather than a race.
-- request_hash exists so the SAME key arriving with DIFFERENT data can be
-- rejected as a client error instead of silently returning someone else's
-- result.
CREATE TABLE idempotency (
    key             TEXT PRIMARY KEY,
    request_hash    TEXT           NOT NULL,
    transfer_id     UUID           NOT NULL REFERENCES transfers(id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    transfer_id     UUID           NOT NULL REFERENCES transfers(id),
    event_type      TEXT           NOT NULL,
    payload         TEXT           NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- The relay reads this. Age of the oldest unpublished row is the metric that
-- matters, which is why the index is on created_at among unpublished rows.
CREATE INDEX outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
