-- =====================
-- ACCOUNTS
-- =====================
CREATE TABLE accounts (
                          id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          email               TEXT NOT NULL UNIQUE,
                          password_hash       TEXT NOT NULL,
                          api_key_hash        TEXT NOT NULL UNIQUE,
                          plan                TEXT NOT NULL DEFAULT 'free'
                              CHECK (plan IN ('free', 'pro')),
                          email_verified      BOOLEAN NOT NULL DEFAULT false,
                          stripe_customer_id  TEXT,
    -- recipient pool tracking for free tier (resets monthly)
                          unique_recipients_this_period   INT NOT NULL DEFAULT 0,
                          period_reset_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
                          created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- MANAGEMENT OAUTH TOKENS
-- =====================
CREATE TABLE management_oauth_tokens (
                                         id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                         account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                                         access_token    TEXT NOT NULL,   -- AES-256 encrypted
                                         refresh_token   TEXT NOT NULL,   -- AES-256 encrypted
                                         expires_at      TIMESTAMPTZ NOT NULL,
                                         created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                         updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- CONNECTIONS
-- =====================
CREATE TABLE connections (
                             id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                             account_id          UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                             name                TEXT NOT NULL,
                             type                TEXT NOT NULL DEFAULT 'supabase'
                                 CHECK (type IN ('supabase', 'postgres')),
                             project_ref         TEXT,        -- from Supabase Management API OAuth
                             project_url         TEXT NOT NULL,
                             encrypted_creds     TEXT NOT NULL, -- AES-256 encrypted connection credentials
                             user_table_schema   TEXT NOT NULL DEFAULT 'public',
                             user_table_name     TEXT NOT NULL,
                             email_column        TEXT NOT NULL,
                             user_id_column      TEXT NOT NULL,
                             estimated_user_count INT,         -- cached, updated opportunistically
                             created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                             updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- prevents same Supabase project connected under multiple accounts
                             UNIQUE (account_id, project_url)
);

-- =====================
-- FILTERABLE COLUMNS
-- =====================
CREATE TABLE filterable_columns (
                                    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    connection_id       UUID NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                                    column_name         TEXT NOT NULL,
    -- UI category not raw Postgres type:
    -- text / boolean / timestamptz / integer
                                    column_type         TEXT NOT NULL,
                                    display_name        TEXT NOT NULL,
                                    enabled             BOOLEAN NOT NULL DEFAULT true,
                                    cardinality         INT,          -- cached distinct value count
                                    cardinality_warning BOOLEAN NOT NULL DEFAULT false, -- true if >50 distinct values
                                    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                    UNIQUE (connection_id, column_name)
);

-- =====================
-- EMAIL PROVIDERS
-- =====================
CREATE TABLE email_providers (
                                 id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 account_id          UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                                 type                TEXT NOT NULL CHECK (type IN ('resend', 'ses')),
                                 encrypted_api_key   TEXT NOT NULL, -- AES-256 encrypted
                                 from_address        TEXT NOT NULL,
                                 from_name           TEXT,
                                 created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- CAMPAIGNS
-- =====================
CREATE TABLE campaigns (
                           id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           account_id       UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                           connection_id    UUID NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                           name             TEXT NOT NULL,
                           subject          TEXT NOT NULL,
                           body_html        TEXT NOT NULL,
                           status           TEXT NOT NULL DEFAULT 'draft'
                               CHECK (status IN (
                                                 'draft', 'sending', 'sent',
                                                 'partially_failed', 'failed'
                                   )),
    -- denormalised counters — updated atomically on webhook events
    -- avoids COUNT() aggregation on campaign_recipients on every dashboard load
                           recipient_count  INT,
                           sent_count       INT NOT NULL DEFAULT 0,
                           delivered_count  INT NOT NULL DEFAULT 0,
                           opened_count     INT NOT NULL DEFAULT 0,
                           bounced_count    INT NOT NULL DEFAULT 0,
                           failed_count     INT NOT NULL DEFAULT 0,
                           scheduled_at     TIMESTAMPTZ,  -- Pro only, V2 backend
                           sent_at          TIMESTAMPTZ,
                           created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                           updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- CAMPAIGN FILTERS
-- =====================
CREATE TABLE campaign_filters (
                                  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  campaign_id     UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                                  column_name     TEXT NOT NULL,
                                  operator        TEXT NOT NULL
                                      CHECK (operator IN ('eq', 'neq', 'gt', 'lt', 'contains')),
                                  filter_value    TEXT NOT NULL,
                                  filter_order    INT NOT NULL DEFAULT 0,  -- for multiple AND filters
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- CAMPAIGN RECIPIENTS
-- =====================
CREATE TABLE campaign_recipients (
                                     id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     campaign_id         UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                                     external_user_id    TEXT NOT NULL,      -- their Supabase user id, no FK possible
                                     email               TEXT NOT NULL,
                                     status              TEXT NOT NULL DEFAULT 'queued'
                                         CHECK (status IN (
                                                           'queued', 'sent', 'delivered',
                                                           'opened', 'bounced', 'failed', 'unsubscribed'
                                             )),
    -- campaign_id + external_user_id — DB-level double-send prevention
                                     idempotency_key     TEXT NOT NULL UNIQUE,
    -- set after Resend accepts the send — used for webhook event matching
                                     resend_message_id   TEXT UNIQUE,
                                     failed_reason       TEXT,
                                     sent_at             TIMESTAMPTZ,
                                     delivered_at        TIMESTAMPTZ,
                                     opened_at           TIMESTAMPTZ,
                                     bounced_at          TIMESTAMPTZ,
                                     created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- OUTBOX
-- =====================
CREATE TABLE outbox (
                        id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        campaign_recipient_id   UUID NOT NULL REFERENCES campaign_recipients(id)
                            ON DELETE CASCADE,
                        status                  TEXT NOT NULL DEFAULT 'pending'
                            CHECK (status IN (
                                              'pending', 'processing', 'done', 'failed'
                                )),
                        attempts                INT NOT NULL DEFAULT 0,
    -- backoff schedule: 1min → 5min → 15min
    -- worker only polls WHERE status = 'pending' AND next_attempt_at <= now()
                        next_attempt_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                        last_attempted_at       TIMESTAMPTZ,
                        created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- WEBHOOK EVENTS
-- =====================
CREATE TABLE webhook_events (
                                id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Resend's own event id — UNIQUE for idempotent deduplication
                                provider_event_id       TEXT NOT NULL UNIQUE,
    -- nullable: recipient may be deleted before event arrives
    -- no CASCADE — webhook_events manages own retention independently
                                campaign_recipient_id   UUID REFERENCES campaign_recipients(id),
                                event_type              TEXT NOT NULL,
    -- delivered / opened / bounced / clicked / failed
                                raw_payload             JSONB NOT NULL,
                                processed               BOOLEAN NOT NULL DEFAULT false,
                                created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================
-- UNSUBSCRIBES
-- =====================
CREATE TABLE unsubscribes (
                              id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                              connection_id   UUID NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                              email           TEXT NOT NULL,
                              unsubscribed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- one unsubscribe record per email per account
    -- permanent — not subject to retention policy
                              UNIQUE (account_id, email)
);

-- =====================
-- INDEXES
-- =====================

-- accounts — hottest reads in the system
CREATE INDEX idx_accounts_email
    ON accounts(email);
CREATE INDEX idx_accounts_api_key_hash
    ON accounts(api_key_hash);

-- management_oauth_tokens
CREATE INDEX idx_management_oauth_tokens_account_id
    ON management_oauth_tokens(account_id);

-- connections
CREATE INDEX idx_connections_account_id
    ON connections(account_id);
-- UNIQUE (account_id, project_url) creates implicit index automatically

-- filterable_columns
CREATE INDEX idx_filterable_columns_connection_id
    ON filterable_columns(connection_id);
-- UNIQUE (connection_id, column_name) creates implicit index automatically

-- email_providers
CREATE INDEX idx_email_providers_account_id
    ON email_providers(account_id);

-- campaigns
CREATE INDEX idx_campaigns_account_id
    ON campaigns(account_id);
CREATE INDEX idx_campaigns_status
    ON campaigns(status);
-- justification: retention cleanup job queries WHERE status = 'sent'
-- AND sent_at < retention_cutoff

-- campaign_filters
CREATE INDEX idx_campaign_filters_campaign_id
    ON campaign_filters(campaign_id);

-- campaign_recipients
CREATE INDEX idx_campaign_recipients_campaign_id
    ON campaign_recipients(campaign_id);
CREATE INDEX idx_campaign_recipients_resend_message_id
    ON campaign_recipients(resend_message_id);
-- justification: webhook event matching — hottest read during active sends
CREATE INDEX idx_campaign_recipients_status
    ON campaign_recipients(status);

-- outbox — most critical index in the schema
-- partial index: done/failed rows fall out automatically
-- worker polls every 5s — slow poll = slow fan-out = unhappy customers
CREATE INDEX idx_outbox_status_next_attempt
    ON outbox(status, next_attempt_at)
    WHERE status = 'pending';

-- webhook_events
-- UNIQUE on provider_event_id creates implicit index automatically
-- partial index: processed rows fall out — index stays tiny despite
-- 3.75M rows/month growth
CREATE INDEX idx_webhook_events_unprocessed
    ON webhook_events(created_at)
    WHERE processed = false;

-- unsubscribes
-- UNIQUE (account_id, email) creates implicit index automatically
-- covers the hot read: SELECT WHERE account_id = ? AND email = ?
-- checked before every recipient is added to campaign snapshot