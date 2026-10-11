/**
 * Retention and legal hold (Phase 1). The retention job must never delete evidence that a legal hold has frozen,
 * even when the hold is placed concurrently: see docs/concurrency/01-write-skew-legal-hold.md.
 * <p>
 * Exception to "cross features only through services": this package runs SQL directly on the {@code evidence}
 * and {@code case_file} tables. Lock modes, lock order and the exact statement shape are the subject of this
 * feature, and they cannot be expressed through another feature's service. Existence checks for API errors
 * (404) still go through {@code CaseFileService}.
 */
package com.evidencevault.retention;
