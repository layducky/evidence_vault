CREATE TABLE case_file (
    id          UUID PRIMARY KEY,
    case_number VARCHAR(64)  NOT NULL UNIQUE,
    title       VARCHAR(255) NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE evidence (
    id            UUID PRIMARY KEY,
    case_id       UUID         NOT NULL REFERENCES case_file (id),
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(127) NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes >= 0),
    sha256        CHAR(64),
    status        VARCHAR(32)  NOT NULL,
    legal_hold    BOOLEAN      NOT NULL DEFAULT FALSE,
    retain_until  TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_case_id ON evidence (case_id);

-- Retention job scans only live, non-held rows that have expired.
CREATE INDEX idx_evidence_retention
    ON evidence (retain_until)
    WHERE deleted_at IS NULL AND legal_hold = FALSE;
