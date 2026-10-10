/**
 * Chain of custody: append-only, hash-chained audit log per case. Phase 2 - concurrent appends must never fork the chain (docs/concurrency/02-hash-chain.md).
 */
package com.evidencevault.custody;
