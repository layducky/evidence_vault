-- Case-level legal hold. Lives in its own table (not a column on evidence) so that placing a hold and purging
-- evidence write different rows: that is what makes the retention race a real write skew (Phase 1).
CREATE TABLE legal_hold (
    id          UUID PRIMARY KEY,
    case_id     UUID         NOT NULL REFERENCES case_file (id),
    reason      VARCHAR(500) NOT NULL,
    placed_by   VARCHAR(128) NOT NULL,
    placed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0
);

-- Retention and hold checks only look at active holds; also serves as the FK index for those lookups.
CREATE INDEX idx_legal_hold_active_case ON legal_hold (case_id) WHERE released_at IS NULL;

-- Same-row invariant for evidence-level holds: enforced by the DB, not by Java.
ALTER TABLE evidence
    ADD CONSTRAINT chk_evidence_not_held_and_deleted CHECK (NOT (legal_hold AND deleted_at IS NOT NULL));
