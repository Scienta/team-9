-- CFP schema. Runs on every start-up (spring.sql.init.mode=always), so everything must be IF NOT EXISTS.
-- Don't edit old statements, add new ones at the bottom.

-- 2019-03 initial version
CREATE TABLE IF NOT EXISTS speaker (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    email      TEXT NOT NULL,
    bio        TEXT,
    created    TEXT          -- dd.MM.yyyy, the old admin UI wrote it like that
);

-- 2019-04 talks
CREATE TABLE IF NOT EXISTS talk (
    id           SERIAL PRIMARY KEY,
    title        TEXT NOT NULL,
    abstract     TEXT,                              -- never exposed, the web form was dropped
    format       TEXT NOT NULL,
    duration     INTEGER,                           -- derived from format, the reporting tool reads it
    level        TEXT,
    status       TEXT NOT NULL DEFAULT 'SUBMITTED',
    speaker_id   INTEGER NOT NULL,
    submitted_at BIGINT                             -- epoch millis (UTC)
);

-- 2019-10 voting, replaced by rating in 2020. Kept for the 2019 statistics.
CREATE TABLE IF NOT EXISTS vote (
    talk_id INTEGER,
    voter   TEXT,
    stars   INTEGER
);

-- 2020-02 ratings
CREATE TABLE IF NOT EXISTS rating (
    talk_id     INTEGER NOT NULL,
    reviewer_id INTEGER NOT NULL,
    score       INTEGER NOT NULL,
    rated_at    TEXT,                               -- yyyy-MM-dd HH:mm:ss (UTC)
    PRIMARY KEY (talk_id, reviewer_id)
);

-- 2020-06 programme planning (never finished)
-- ALTER TABLE talk ADD COLUMN room TEXT;
-- ALTER TABLE talk ADD COLUMN slot TEXT;

-- 2021-09 after the CSV import created duplicate speakers
CREATE UNIQUE INDEX IF NOT EXISTS ux_speaker_email ON speaker (lower(email));

-- 2022-01 GET /speakers/{id}/talks was slow
CREATE INDEX IF NOT EXISTS ix_talk_speaker ON talk (speaker_id);

-- Co-speakers, ordered independently of speaker ids. Existing talks have none.
CREATE TABLE IF NOT EXISTS talk_co_speaker (
    talk_id INTEGER NOT NULL REFERENCES talk(id),
    speaker_id INTEGER NOT NULL REFERENCES speaker(id),
    added_order BIGSERIAL NOT NULL,
    PRIMARY KEY (talk_id, speaker_id)
);

-- Workshop registrations (workflow/specs/workshop-registration.md). One per email per workshop,
-- whatever the letter case; ordered by id, which is registration order.
CREATE TABLE IF NOT EXISTS workshop_registration (
    id            SERIAL PRIMARY KEY,
    talk_id       INTEGER NOT NULL REFERENCES talk(id),
    name          TEXT NOT NULL,
    email         TEXT NOT NULL,                    -- as the attendee typed it
    registered_at BIGINT NOT NULL                   -- epoch millis (UTC)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_workshop_registration_email ON workshop_registration (talk_id, lower(email));

-- Scheduling (workflow/specs/scheduling.md). Rooms, slots and their times are fixed in code
-- (Programme); programme_slot has one row per slot so a PUT can lock its slot (R7, R9).
CREATE TABLE IF NOT EXISTS programme_slot (
    slot TEXT PRIMARY KEY
);
INSERT INTO programme_slot (slot) VALUES ('S1'), ('S2'), ('S3'), ('S4'), ('S5') ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS programme (
    room    TEXT NOT NULL,
    slot    TEXT NOT NULL REFERENCES programme_slot(slot),
    talk_id INTEGER NOT NULL REFERENCES talk(id),
    PRIMARY KEY (room, slot)                        -- one talk per room and slot (R5)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_programme_talk ON programme (talk_id);   -- on the programme once (R6)
