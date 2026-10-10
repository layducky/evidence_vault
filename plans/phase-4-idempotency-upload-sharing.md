# Phase 4 – Idempotency, Upload, Sharing, ETag, Deadlock

> Status: ⬜ Not started · Roadmap: `docs/PLAN.md` §3 Phase 4 · Implement with `/impl-phase 4 [step]`
> Rules: `rules/coding/concurrency.md`, `rules/coding/db.md`, `rules/testing/TEST_STRATEGY.md`

## 1. Goal
Five small problems. Each has a naive version, a reproduction test, a fix, and its own doc (05–09):
1. **Idempotency-Key**: a client resending the same request does not create a duplicate record.
2. **Chunked upload + exactly-once finalize**, SHA-256 dedup via a unique constraint.
3. **Share link with a view limit** that is never exceeded.
4. **ETag / If-Match**: prevents lost updates when editing metadata.
5. **Transferring evidence between two cases**: reproduce a deadlock, then fix it with lock ordering.

## 2. Scope
**In scope:** all 5 items above, the APIs, a minimal upload UI (optional).
**Out of scope:** real MinIO or S3, presigned URLs. Files are stored through the `BlobStore` interface; the default
implementation is `LocalFileBlobStore` (configurable directory). Reason: the app runs locally without Docker. The
concurrency problem lives in the DB, not in where files are stored.

## 3. Prerequisites
- Phases 1 to 3 done. After upload, an `EVIDENCE_PROCESS` job (Phase 3) is enqueued to compute and verify the SHA-256.

## 4. Changes vs PLAN.md
- Replace MinIO with `BlobStore` (as above). To use S3/MinIO later, add another implementation without changing the logic.
- The deadlock problem is tied to a real business operation: transferring evidence must **lock both cases** (to check
  the Phase 1 legal hold consistently). The naive version locks source then target, so A→B and B→A running in parallel deadlock.

## 5. Design

### Schema (tentative; version numbers fixed at implementation time)
- `V6__idempotency.sql`:
  `idempotency_key(key VARCHAR(128), scope VARCHAR(64), request_hash CHAR(64), status VARCHAR(16) CHECK (status IN ('IN_PROGRESS','COMPLETED')), response_status INT, response_body JSONB, created_at, expires_at, PRIMARY KEY (scope, key))`.
- `V7__upload.sql`:
  `blob(sha256 CHAR(64) PRIMARY KEY, size_bytes BIGINT, storage_key TEXT, created_at)`;
  `upload_session(id UUID PK, evidence_id UUID FK UNIQUE, status VARCHAR(16) CHECK (status IN ('UPLOADING','FINALIZING','DONE','FAILED')), total_chunks INT, version BIGINT, created_at)`;
  `upload_chunk(upload_id UUID FK, idx INT, size_bytes BIGINT, sha256 CHAR(64), PRIMARY KEY (upload_id, idx))`;
  `evidence.blob_sha256 CHAR(64) REFERENCES blob(sha256)`.
- `V8__share_link.sql`:
  `share_link(token VARCHAR(64) PRIMARY KEY, evidence_id UUID FK, remaining INT NOT NULL CHECK (remaining >= 0), expires_at TIMESTAMPTZ NOT NULL, created_at)`.

### API
| Method | Path | Success | Errors |
|---|---|---|---|
| POST (every creating POST) | header `Idempotency-Key` | original response (replayed on duplicate) | 409 in progress, 422 same key with different body |
| POST | `/api/evidence/{id}/uploads` `{totalChunks}` | 201 `{uploadId}` | 404, 409 upload session already exists |
| PUT | `/api/uploads/{uploadId}/chunks/{idx}` (binary) | 204 | 409 session no longer `UPLOADING` |
| POST | `/api/uploads/{uploadId}/complete` | 200 `{sha256, deduplicated}` | 409 finalizing or already finalized, 422 missing chunk |
| POST | `/api/evidence/{id}/share-links` `{maxViews, ttl}` | 201 `{token, url}` | 404 |
| GET | `/share/{token}` | 200 file stream | 404, 410 views exhausted or expired |
| GET | `/api/evidence/{id}` | 200 + `ETag: "<version>"` | 404 |
| PATCH | `/api/evidence/{id}` + `If-Match` | 200 + new ETag | 412 stale version, 428 missing `If-Match` |
| POST | `/api/evidence/{id}/transfer` `{targetCaseId}` | 200 | 404, 409 source or target is on hold |

### Fix per problem
| Problem | Naive (wrong) | Fix |
|---|---|---|
| Idempotency | `SELECT key` → if absent, process then insert | `INSERT … ON CONFLICT DO NOTHING` with `IN_PROGRESS` **before** processing; the winner processes and then stores the response; losers read the status and return 409, replay the response, or 422 |
| Finalize | read `status == UPLOADING`, then assemble the file | `UPDATE upload_session SET status='FINALIZING' WHERE id=? AND status='UPLOADING'`, only the caller that gets 1 row assembles |
| Dedup | `SELECT blob WHERE sha256`, then insert | `INSERT INTO blob … ON CONFLICT (sha256) DO NOTHING`, then point the evidence at the blob |
| Share link | read `remaining > 0`, then `remaining - 1` | `UPDATE share_link SET remaining = remaining - 1 WHERE token=? AND remaining > 0 AND expires_at > now() RETURNING evidence_id` |
| ETag | overwrite without checking | `UPDATE … WHERE id=? AND version=:ifMatch`, 0 rows gives 412 (or JPA `@Version`) |
| Transfer | lock source, then target | lock both cases `ORDER BY id FOR NO KEY UPDATE` in a single statement |

### New classes
| Class | Package | Role | Transaction / isolation |
|---|---|---|---|
| `IdempotencyFilter`, `IdempotencyStore` | `idempotency` | filter for POSTs with the header; hashes the body with SHA-256; stores and replays the response | claim and storing the result are **two separate short transactions**; no lock held while the request is processed |
| `BlobStore`, `LocalFileBlobStore` | `upload` | `putChunk`, `assemble`, `open`, `delete` | – |
| `UploadService`, `UploadController` | `upload` | upload session, chunks, finalize | finalize: READ COMMITTED + conditional UPDATE |
| `ShareLinkService`, `ShareController` | `sharing` | create link, consume one view | a single UPDATE … RETURNING |
| `EvidenceMetadataController` | `evidence` | GET/PATCH with ETag | READ COMMITTED + version check |
| `EvidenceTransferService` | `evidence` | transfer evidence, check hold, write custody event | READ COMMITTED + locks in id order |

## 6. Steps (code + tests together)

### - [ ] 4.1 Idempotency-Key
- **Code:** migration V6, `IdempotencyStore`, `IdempotencyFilter` (applied to `POST /api/cases` and `POST …/evidence`).
- **Test:** `IdempotencyStoreTest` (Unit): T-4.1 the body hash is stable and independent of JSON key order (uses `CanonicalJson` from Phase 2).
  `IdempotencyTest` (DB, MockMvc):
  T-4.2 resending after completion replays the same status and body and creates no new record;
  T-4.3 same key with a different body gives 422;
  T-4.4 20 requests with the same key sent in parallel (`Concurrently`): exactly 1 record is created, the other requests get 409 or the replayed response;
  T-4.5 `naive_…` (deterministic interleaving) **creates 2 duplicate records**;
  T-4.6 if the original request fails with 5xx, the key is deleted so the client can retry.
- **Done when:** T-4.1 to T-4.6 pass.

### - [ ] 4.2 `BlobStore` and chunked upload
- **Code:** `LocalFileBlobStore` (directory `evidence-vault.blob.dir`, default `./data/blobs`, git-ignored), migration V7,
  `UploadService` (create session, receive chunks, idempotent by `(upload_id, idx)`).
- **Test:** `LocalFileBlobStoreTest` (Unit, `@TempDir`): T-4.7 assembles in the correct order with the correct SHA-256; T-4.8 rewriting the same
  chunk is idempotent. `UploadTest` (DB): T-4.9 uploading 3 chunks then completing makes the evidence point to the blob with the correct SHA; T-4.10 a missing chunk gives 422.
- **Done when:** T-4.7 to T-4.10 pass.

### - [ ] 4.3 Exactly-once finalize and dedup
- **Test:** `UploadConcurrencyTest` (DB):
  T-4.11 `naive_…` two concurrent complete calls **assemble twice** (count the number of `assemble` calls);
  T-4.12 fixed version: 10 parallel complete calls give exactly 1 `assemble`; the other calls get 409 (or 200 if already `DONE`);
  T-4.13 two different evidence items uploading the same content in parallel produce only 1 `blob` row, both point to it, no error.
- **Done when:** T-4.11 to T-4.13 pass. After finalize, enqueue `EVIDENCE_PROCESS` (Phase 3) to re-verify the SHA-256.

### - [ ] 4.4 Share link with a view limit
- **Code:** migration V8, `ShareLinkService`, `ShareController` (random 32-byte token, base64url).
- **Test:** `ShareLinkTest` (DB):
  T-4.14 `naive_…` `maxViews = 5`, 50 parallel views give **more than 5 successes**;
  T-4.15 fixed version: exactly 5 successes, 45 get 410, `remaining = 0`;
  T-4.16 expired (`MutableClock`) gives 410;
  T-4.17 nonexistent token gives 404.
- **Done when:** T-4.14 to T-4.17 pass, T-4.15 passes twice in a row.

### - [ ] 4.5 ETag / If-Match
- **Code:** `EvidenceMetadataController` for GET (ETag) and PATCH (`fileName`, `contentType`, `retainUntil`).
- **Test:** `EvidenceETagTest` (DB, MockMvc): T-4.18 PATCH with the correct ETag gives 200 and the ETag increases; T-4.19 a stale ETag gives 412,
  data unchanged; T-4.20 missing `If-Match` gives 428; T-4.21 two clients read and then PATCH: one 200, one 412 (no lost update).
- **Done when:** T-4.18 to T-4.21 pass.

### - [ ] 4.6 Evidence transfer and deadlock
- **Code:** `EvidenceTransferService` with `NaiveTransfer` (locks source then target, with a `pauseAfterFirstLock` hook) and the fixed version (locks both `ORDER BY id`).
  Transfer is refused if either case is on hold (409). Writes a custody event on both cases.
- **Test:** `EvidenceTransferTest` (DB):
  T-4.22 `naive_…` transferring A→B and B→A in parallel, ordered with latches, **one side gets a `40P01` deadlock**;
  T-4.23 fixed version with the same scenario: both complete, no deadlock;
  T-4.24 source case on hold gives 409, evidence unchanged;
  T-4.25 stress of 200 random transfers among 5 cases: no deadlock, total evidence count unchanged.
- **Done when:** T-4.22 to T-4.25 pass.

### - [ ] 4.7 Docs and benchmark
- **Code:** `@Tag("bench")` benchmarks for share links (views/second under contention) and idempotency (filter overhead).
- **Docs:** `docs/concurrency/05-idempotency.md`, `06-upload-finalize.md`, `07-share-link.md`, `08-etag.md`,
  `09-deadlock-lock-ordering.md`; `docs/ARCHITECTURE.md`; tick `docs/PLAN.md`.
- **Done when:** `./mvnw verify` is green, all 5 docs exist with numbers.

### - [ ] 4.8 (Optional) Upload and share link UI
- **Code:** upload form that splits chunks with `File.slice`, a button to create a share link. Manual check.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-4.1 | Unit | `IdempotencyStoreTest` | body hash | stable |
| T-4.2 → T-4.6 | DB | `IdempotencyTest` | replay, 422, parallel, naive, 5xx error | exactly 1 record |
| T-4.7, T-4.8 | Unit | `LocalFileBlobStoreTest` | assemble, idempotent | correct SHA |
| T-4.9, T-4.10 | DB | `UploadTest` | upload flow, missing chunk | 200 / 422 |
| T-4.11 → T-4.13 | DB | `UploadConcurrencyTest` | naive, 10 completes, dedup | exactly 1 assemble, 1 blob |
| T-4.14 → T-4.17 | DB | `ShareLinkTest` | naive, fixed, expired, 404 | exactly `maxViews` |
| T-4.18 → T-4.21 | DB | `EvidenceETagTest` | 200, 412, 428, 2 clients | no lost update |
| T-4.22 → T-4.25 | DB | `EvidenceTransferTest` | naive deadlock, fixed, hold, stress | no deadlock |

## 8. Docs to update
- `docs/concurrency/05` to `09`, `docs/ARCHITECTURE.md`, `docs/PLAN.md`, `.gitignore` (`data/`).

## 9. Definition of Done
- [ ] `./mvnw verify` green
- [ ] Each problem has a naive test that reproduces the bug and a passing fix test
- [ ] All 5 docs written

## 10. Open questions
- How long until an idempotency key expires? Proposal: 24 hours, cleaned up by a Phase 3 job.
- Apply `Idempotency-Key` to every POST or only to creating endpoints? Proposal: only creating endpoints.
- Chunk and file size limits? Proposal: max 8 MB per chunk, configured via `spring.servlet.multipart`.
