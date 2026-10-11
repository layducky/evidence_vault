package com.evidencevault.retention;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC parameters: the Postgres driver binds {@link OffsetDateTime} to TIMESTAMPTZ, but not {@link Instant}. */
final class Timestamps {

    private Timestamps() {
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
