-- Vobiz call-masking schema (PostgreSQL). Idempotent: safe to run on every boot
-- (spring.sql.init.mode=always).

-- Agents = the real numbers (N2, N3, ...) that must stay hidden from callers.
CREATE TABLE IF NOT EXISTS agents (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    phone_e164  VARCHAR(32)  NOT NULL UNIQUE,
    priority    INT          NOT NULL DEFAULT 0,          -- lower rings first in sequential mode
    status      VARCHAR(16)  NOT NULL DEFAULT 'available'
                CHECK (status IN ('available', 'busy', 'offline')),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every call leg (inbound to M1, and outbound click-to-call legs).
-- call_uuid is the join key to Vobiz CDRs.
CREATE TABLE IF NOT EXISTS calls (
    id              BIGSERIAL PRIMARY KEY,
    call_uuid       VARCHAR(64) UNIQUE,
    direction       VARCHAR(16) NOT NULL DEFAULT 'inbound'
                    CHECK (direction IN ('inbound', 'outbound')),
    caller_number   VARCHAR(32),
    dialed_number   VARCHAR(32),
    agent_id        BIGINT REFERENCES agents(id),          -- set when a single agent took the call
    dialed_agents   JSONB,                                 -- agents that were rung, in order
    status          VARCHAR(16) NOT NULL DEFAULT 'in_progress'
                    CHECK (status IN ('in_progress', 'answered', 'no_answer', 'voicemail', 'failed')),
    started_at      TIMESTAMPTZ,
    answered_at     TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    duration_sec    INT,
    hangup_cause    VARCHAR(64),
    recording_url   TEXT,
    callback_id     BIGINT,                                -- set on outbound callback legs
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_calls_status  ON calls(status);
CREATE INDEX IF NOT EXISTS idx_calls_created ON calls(created_at DESC);

-- "Who do we owe a call": one row per voicemail.
CREATE TABLE IF NOT EXISTS callback_requests (
    id                      BIGSERIAL PRIMARY KEY,
    call_id                 BIGINT REFERENCES calls(id),
    caller_number           VARCHAR(32) NOT NULL,
    voicemail_url           TEXT,
    voicemail_duration_sec  INT,
    status                  VARCHAR(16) NOT NULL DEFAULT 'pending'
                            CHECK (status IN ('pending', 'calling', 'called_back', 'closed')),
    assigned_agent_id       BIGINT REFERENCES agents(id),
    notes                   TEXT,
    outbound_call_uuid      VARCHAR(64),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at               TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_callbacks_status ON callback_requests(status);

-- Key/value settings edited from the ops console.
CREATE TABLE IF NOT EXISTS settings (
    key    VARCHAR(64) PRIMARY KEY,
    value  TEXT NOT NULL
);

INSERT INTO settings(key, value) VALUES
    ('m1_number', '+10000000000'),
    ('ring_timeout_sec', '20'),
    ('voicemail_max_sec', '30'),
    ('record_silence_timeout_sec', '5'),
    ('routing_mode', 'simultaneous'),                          -- simultaneous | sequential
    ('respect_presence', 'false'),                             -- true: only ring agents marked 'available'
    ('announce_recording', 'true'),                            -- TRAI: announce before bridging
    ('announcement_text', 'This call may be recorded for quality and training purposes.'),
    ('greeting_text', 'Sorry, none of our team is free right now. Please tell us your issue after the beep, and someone will call you back.'),
    ('closing_text', 'Thank you. We will call you back shortly.')
ON CONFLICT (key) DO NOTHING;

-- Demo agents (only inserted when the phone number is not already present).
INSERT INTO agents(name, phone_e164, priority, status)
SELECT * FROM (VALUES
    ('Agent N2', '+15551230001', 1, 'available'),
    ('Agent N3', '+15551230002', 2, 'available')
) AS v(name, phone_e164, priority, status)
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE phone_e164 = v.phone_e164);
