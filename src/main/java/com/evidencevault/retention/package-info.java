/**
 * Retention and legal hold. Phase 1 - the retention job must never delete evidence under legal hold, even when a hold is placed concurrently (write skew, docs/concurrency/01-write-skew-legal-hold.md).
 */
package com.evidencevault.retention;
