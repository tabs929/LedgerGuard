# API Specification

> **Status: Phase 1 (Tasks 1–9) complete. Phase 2, Task 10 (idempotency
> for deposits and transfers), Task 11 (transactional outbox), Task 12
> (Kafka publishing of outbox events), Task 13 (Kafka consumption and
> duplicate-event protection), and Task 14 (settlement CSV import)
> implemented.** None of Tasks 11–13 add any new endpoint, request field,
> response field, status code, or header — the outbox, its Kafka
> publisher, and its Kafka consumer are all internal
> persistence/background-processing details (see `docs/ARCHITECTURE.md`'s
> "Transactional Outbox", "Kafka Publishing", and "Kafka Consumption"
> sections); every contract for the five Phase 1/Task 10 endpoints below
> is unchanged from Task 10. `POST /api/v1/accounts`,
> `POST /api/v1/accounts/{id}/deposits`, `POST /api/v1/transfers`, `GET
> /api/v1/accounts/{id}/balance`, `GET
> /api/v1/accounts/{id}/transactions`, and the new `POST
> /api/v1/settlement-imports` (Task 14) all exist and match the contracts
> below exactly. Both deposits and transfers are USD-only and always post
> a balanced double-entry ledger transaction in the same database
> transaction as the materialized balance updates; the two read endpoints
> never modify that state. `POST /api/v1/accounts/{id}/deposits` and
> `POST /api/v1/transfers` additionally require an `Idempotency-Key`
> request header (Task 10) — see each endpoint's section below and
> "Idempotency" further down; `POST /api/v1/settlement-imports` does
> **not** use that header — its own idempotent-replay behavior is keyed
> on the uploaded file's content, not a caller-supplied header (see its
> section below). Every documented error response, from every endpoint,
> goes through one centralized `@RestControllerAdvice`
> (`common.GlobalExceptionHandler`) and matches the "Error Response Shape"
> section below exactly. Every endpoint, every request/response
> schema, and this exact error envelope are also machine-readable at
> `GET /v3/api-docs` (OpenAPI 3.1 JSON) and browsable at
> `GET /swagger-ui/index.html` — see "OpenAPI/Swagger" below. Plain
> `GET /api/v1/accounts/{id}` remains unimplemented — no task has been
> assigned it so far — no controller, service, or DTO exists for it yet.
> Reconciliation and authentication remain unimplemented — see
> `docs/TASKS.md` for what Tasks 15+ still cover. `processed_event` is
> never exposed as a public resource — there is no consumer
> administration, replay, or health-detail endpoint; likewise
> `settlement_import`/`settlement_record` have no list, get, retry,
> update, delete, reconciliation, or administration endpoint — `POST
> /api/v1/settlement-imports` is the only settlement endpoint.

All endpoints are versioned under `/api/v1` and are unauthenticated in
Phase 1 (authentication is a Phase 3 concern).

## POST /api/v1/accounts (implemented, Task 3)

Request: `{ "ownerName": string, "currency": "USD" }`

Response 201: `{ "id": uuid, "ownerName": string, "currency": string, "balance": "0.0000", "createdAt": iso8601 }`

Creates a customer wallet account only. The server always assigns
`account_category = CUSTOMER`, `account_class = LIABILITY`,
`account_purpose = CUSTOMER_WALLET`, and `balance = 0` — the request has no
fields for any of these, so a client cannot select or override them, and
cannot create a `SYSTEM` account through this endpoint.

`currency` accepts `"USD"` case-insensitively (`"usd"` is normalized to
`"USD"` in the response); any other value is rejected. Any JSON property
not in the request shape above (e.g. an attempt to set `balance`,
`accountCategory`, `id`, or `createdAt`) is rejected outright as a 400 —
never silently ignored.

Errors:
- 400 validation — missing/blank `ownerName`, missing or malformed
  `currency` (not a 3-letter code), or an unrecognized JSON property.
- 422 unsupported currency — a well-formed but non-`USD` currency code
  (only `USD` is accepted in Phase 1).

## GET /api/v1/accounts/{id} (not implemented)

Response 200: same shape as account creation. Never returns a `SYSTEM`
account.

Response 404: not found — including when `{id}` is a `SYSTEM` account, which
is treated identically to a nonexistent id (see `docs/ARCHITECTURE.md` on
public vs. internal account lookup).

## POST /api/v1/accounts/{id}/deposits (implemented, Task 4; idempotent, Task 10)

Required header: `Idempotency-Key: <1-128 chars from [A-Za-z0-9._:-]>`

Request: `{ "amount": "100.00", "currency": "USD" }`

Response 201: `{ "transactionId": uuid, "accountId": uuid, "amount": "100.00", "currency": "USD", "newBalance": "100.00", "createdAt": iso8601 }`

A deposit is a single atomic, balanced double-entry ledger transaction:
DEBIT the internal `SYSTEM`/`ASSET`/`EXTERNAL_FUNDING` account, CREDIT the
`{id}` customer wallet, both for the same amount and currency, both
referencing the same new `ledger_transaction` row
(`transaction_type = DEPOSIT`, `status = COMPLETED`). Both accounts'
materialized balances increase by the deposit amount in the same database
transaction as the two ledger-entry inserts — never independently of them.
The request has no field for the funding account, transaction id/type/
status, entry direction, ledger-entry ids, balances, timestamps, or account
taxonomy, so none of these can be supplied or overridden by the client; any
unrecognized JSON property is rejected outright (400), not silently
ignored. `amount` accepts up to 15 integer digits and exactly 4 decimal
digits (matching `NUMERIC(19,4)`) — anything outside that shape is
rejected as a validation error rather than silently rounded.

Errors:
- 400 validation — missing/blank/too-long/invalid-character
  `Idempotency-Key` header, missing/non-positive/malformed `amount`, an
  amount with unsupported precision or scale, missing or malformed
  `currency`, or an unrecognized JSON property.
- 404 account not found — including when `{id}` is a `SYSTEM` account
  (treated identically to a nonexistent id) or any account that is not a
  `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`.
- 409 idempotency conflict — `Idempotency-Key` was already used for a
  command with a different amount, currency, or account, or was already
  used against `POST /api/v1/transfers`. See "Idempotency" below.
- 422 currency mismatch — `currency` is a well-formed but non-`USD` code,
  or (in principle) the destination account's own currency is not `USD`.

## POST /api/v1/transfers (implemented, Task 5; idempotent, Task 10)

Required header: `Idempotency-Key: <1-128 chars from [A-Za-z0-9._:-]>`

Request: `{ "sourceAccountId": uuid, "destinationAccountId": uuid, "amount": "50.00", "currency": "USD" }`

Response 201: `{ "transactionId": uuid, "sourceAccountId": uuid, "destinationAccountId": uuid, "amount": "50.00", "currency": "USD", "createdAt": iso8601 }`

A transfer is a single atomic, balanced double-entry ledger transaction
between two customer wallets: DEBIT the source
`CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET` account, CREDIT the destination
account of the same taxonomy, both for the same amount and currency, both
referencing the same new `ledger_transaction` row
(`transaction_type = TRANSFER`, `status = COMPLETED`). The source's
materialized balance decreases and the destination's increases by the
transfer amount, in the same database transaction as the two ledger-entry
inserts — the combined balance of the two accounts is unchanged by the
operation. The `EXTERNAL_FUNDING` account is never involved in a transfer.
Both accounts are row-locked in ascending account-id order (not
source-then-destination), which is what lets two opposite-direction
transfers between the same two accounts (A→B and B→A) proceed concurrently
without deadlocking.

The request has no field for transaction id/type/status, entry direction,
ledger-entry ids, account balances, timestamps, or account taxonomy, so
none of these can be supplied or overridden by the client; any
unrecognized JSON property is rejected outright (400), not silently
ignored. Unlike deposits, the response has no balance field. `amount`
accepts up to 15 integer digits and exactly 4 decimal digits (matching
`NUMERIC(19,4)`) — anything outside that shape is rejected as a validation
error rather than silently rounded. A transfer for exactly the source's
full balance is allowed and leaves it at exactly zero.

Errors:
- 400 validation — missing/blank/too-long/invalid-character
  `Idempotency-Key` header, missing/non-positive/malformed `amount`, an
  amount with unsupported precision or scale, missing source/destination
  id, missing or malformed `currency`, or an unrecognized JSON property.
- 404 account not found — either id being a `SYSTEM` account (treated
  identically to a nonexistent id) or any account that is not a
  `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`.
- 409 idempotency conflict — `Idempotency-Key` was already used for a
  command with a different amount, currency, or account, or was already
  used against `POST /api/v1/accounts/{id}/deposits`. See "Idempotency"
  below.
- 422 insufficient funds — `source.balance < amount`.
- 422 currency mismatch — `currency` is a well-formed but non-`USD` code,
  or (in principle) either account's own currency is not `USD`.
- 422 source == destination.

## GET /api/v1/accounts/{id}/balance (implemented, Task 6)

Response 200: `{ "accountId": uuid, "balance": "150.00", "currency": "USD" }`

`balance` is the persisted materialized `account.balance` — the same
column deposits and transfers maintain atomically — read directly, never
recomputed or cached. Serialized at whatever scale it's stored at
(`NUMERIC(19,4)`, so typically 4 decimal places, e.g. `"150.0000"`), not
rounded to 2. This is a plain read: no ledger transaction or entry is
created, and no balance is modified, by a `GET` request.

Response 404: not found — including when `{id}` is a `SYSTEM` account
(treated identically to a nonexistent id) or any account that is not
`CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`.

Response 400: `{id}` is not a syntactically valid UUID — this is a
malformed-request error (same category as a malformed `amount` elsewhere
in this spec), not a not-found error, and is Spring's standard behavior
for a path variable that fails to convert to its declared type; no extra
code maps it to 404.

## GET /api/v1/accounts/{id}/transactions?page=&size= (implemented, Task 6)

Returns the requested account's own ledger-entry history — every
`ledger_entry` row posted to that `account_id`, and only that account's
rows. A deposit's customer-wallet `CREDIT` entry appears; the funding
account's `DEBIT` entry never appears here (it belongs to
`EXTERNAL_FUNDING`'s own history, which isn't publicly readable at all,
per the `balance` endpoint's `SYSTEM`-as-404 rule above). For a transfer,
the source account sees its own `DEBIT` entry and the destination account
sees its own `CREDIT` entry — both reference the same
`ledger_transaction`, but each account's history only ever shows its own
one entry from that transaction, never the counterparty's.

**Ordering (approved contract):** newest first —
`ORDER BY ledger_entry.created_at DESC, ledger_entry.id DESC`. The entry's
own `created_at` is the primary sort key; the entry's own `id` is the
deterministic tie-breaker for entries with an identical timestamp. This is
a fixed, non-configurable ordering — there is no `sort` query parameter.

**Pagination (approved contract):**
- Query parameters: `page` (zero-based, default `0`, must be `>= 0`) and
  `size` (default `20`, must be `1..100` inclusive).
- Malformed values (non-numeric), `page < 0`, `size <= 0`, and `size > 100`
  all return `400 Bad Request`.
- Response 200 body — a custom envelope, not Spring Data's default `Page`
  JSON shape:
  ```json
  {
    "content": [
      { "transactionId": "uuid", "entryType": "DEBIT", "amount": "50.00", "currency": "USD", "createdAt": "iso8601" }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
  ```
  `content` items follow the ordering above. `transactionId` is the
  entry's owning `ledger_transaction` id (not the entry's own id — the
  entry's own id is used only as the internal sort tie-breaker and is not
  itself part of the response shape). No counterparty fields are included
  — the contract above is exhaustive per item.

Response 404: not found — same rule as the balance endpoint above
(`SYSTEM` account id or nonexistent id).

Response 400: `{id}` is not a syntactically valid UUID, or `page`/`size` is
malformed or out of bounds — regardless of whether `{id}` would otherwise
resolve (malformed pagination parameters are rejected before the account
is even looked up).

## POST /api/v1/settlement-imports (implemented, Task 14)

Imports a CSV file of external settlement observations. **Records
immutable observations only — never reconciles them against the ledger,
and never mutates any account, ledger, outbox, processed-event, or
idempotency state.** See `docs/ARCHITECTURE.md`'s "Settlement Import"
section for the full duplicate/conflict/concurrency contract.

Request: `multipart/form-data` with two parts —
- `source` (text, required): external provider identifier, non-blank, at
  most `ledgerguard.settlement.import.max-source-length` characters
  (default 64).
- `file` (required): a CSV file matching the exact header
  `external_reference,transaction_id,amount,currency,settled_at` — see
  "Settlement CSV Contract" below.

Response 201 (a new import was recorded):
```
{
  "importId": uuid,
  "source": string,
  "fileHash": string (64-character lowercase hex),
  "totalRows": integer,
  "insertedRows": integer,
  "duplicateRows": integer,
  "replayed": false,
  "importedAt": iso8601
}
```

Response 200 (the exact same file bytes were already imported from this
source): identical shape, `replayed: true`, and every other field
reflects the **original** committed import — not a new one.

Response 400: missing/blank `source`, `source` too long, missing/empty
file, empty or header-only CSV, malformed CSV (bad quoting, wrong column
count, duplicate/missing/reordered/unknown header), or an invalid row
(blank/oversized/non-printable `external_reference`, non-canonical
`transaction_id`, malformed/non-positive/wrong-scale `amount`,
unsupported/malformed `currency`, or an offset-less `settled_at`) —
including a repeated `external_reference` within the same file.

Response 409: a row's `(source, external_reference)` identity conflicts
with a previously stored settlement observation (different transaction
id, amount, currency, or timestamp) — the entire import is rejected, and
the original observation is left unchanged.

Response 413: the file, or its row count, exceeds
`ledgerguard.settlement.import.max-file-size-bytes`/`max-row-count`
(defaults 5 MiB / 10 000 rows).

Response 415: the uploaded file part's content type is not one of a
small accepted set (`text/csv`, `application/csv`, `text/plain`,
`application/octet-stream`, or absent).

Response 503: `ledgerguard.settlement.import.enabled` is `false`. No
other endpoint is affected by this flag.

### Settlement CSV Contract

Exact header, in this exact order, every time:
```
external_reference,transaction_id,amount,currency,settled_at
```

| Field | Rule |
|---|---|
| `external_reference` | Required, trimmed, non-blank, printable text with no control characters, at most `max-external-reference-length` characters (default 128). |
| `transaction_id` | Required, canonical UUID text. Accepted whether or not it matches an existing LedgerGuard transaction — an unmatched reference is retained, not rejected. |
| `amount` | Required, plain decimal string with exactly two decimal places, greater than zero. No scientific notation, no locale-specific grouping. Never compared against the referenced transaction's actual amount. |
| `currency` | Required, exactly three uppercase ASCII letters, and a currency LedgerGuard supports (USD only in Phase 1). Never compared against the referenced transaction's actual currency. |
| `settled_at` | Required, ISO-8601 instant with an explicit UTC offset (a bare local timestamp is rejected). |

UTF-8 only, with an optional leading BOM accepted. CRLF and LF line
endings, standard CSV quoting/escaped-quotes/embedded commas/embedded
newlines inside quoted fields (Apache Commons CSV — never hand-rolled
comma-splitting). Raw field values are never reflected into an error
message, log line, or API response.

## Idempotency (implemented, Task 10)

`POST /api/v1/accounts/{id}/deposits` and `POST /api/v1/transfers` both
require an `Idempotency-Key` request header — the only two write endpoints
in Phase 1/2, and the only two this applies to. Account creation and both
read endpoints do not accept or document this header.

- **Format:** 1–128 characters, matching `^[A-Za-z0-9._:-]{1,128}$`.
  Missing, blank, too long, or containing a disallowed character all
  return `400`.
- **Scope:** a key is scoped to the exact command it was first used for —
  operation type (deposit vs. transfer), account(s), amount, and currency.
  Amount comparison is numeric (`"100"`, `"100.0"`, and `"100.00"` are the
  same command), not a string match; currency comparison is
  case-insensitive (normalized the same way the endpoints themselves
  normalize it).
- **Replay:** a request with a key that exactly matches an already-claimed
  command returns the *exact original* response — same status code, same
  body, byte-for-byte — without creating a new `ledger_transaction`, new
  `ledger_entry` rows, or any balance change. Safe to retry any number of
  times.
- **Conflict:** a request with a key that was already claimed by a
  *different* command (different amount, currency, account, or operation
  type — including a deposit key reused against `/transfers` or vice
  versa) returns `409 Conflict` using the shared `ApiError` envelope, and
  performs no financial write.
- **Failed attempts don't consume the key:** if the underlying deposit or
  transfer fails for any reason (validation, insufficient funds, account
  not found, or a persistence-layer failure), no idempotency record is
  ever committed — the same key can be retried, including with corrected
  request data, and will be treated as new.
- **Retention:** claimed keys are kept indefinitely in Phase 2 — there is
  no expiry, cleanup job, or delete endpoint.

See `docs/ARCHITECTURE.md`'s "Idempotency" section for the transactional
and concurrency mechanics, and `docs/DATA_MODEL.md` for the `idempotency_key`
table.

## Error Response Shape (implemented, Task 7)

All error responses, from every endpoint above, share exactly one shape —
no more, no fewer fields, and no per-endpoint variation:

```json
{
  "timestamp": "iso8601",
  "status": 404,
  "error": "Not Found",
  "message": "...",
  "path": "/api/v1/accounts/..."
}
```

- `timestamp` — an ISO-8601 instant (e.g. `"2026-07-30T19:20:00.123456Z"`),
  captured when the error is translated into a response, in UTC.
- `status` — the numeric HTTP status code, matching the response's actual
  status line exactly.
- `error` — the HTTP reason phrase for that status (`"Not Found"`,
  `"Bad Request"`, `"Unprocessable Content"`, `"Internal Server Error"`).
- `message` — a human-readable, safe description of the failure. For a
  request with multiple field-level validation problems, this is a single
  string listing each one, deterministically ordered by field name (e.g.
  `"amount: must be greater than 0; currency: currency must be a 3-letter
  ISO 4217 code"`) — there is no separate structured field-error list or
  error-code field; none is part of this contract.
- `path` — the request URI that produced the error.

No internal exception details are ever exposed: no Java class or package
names, no stack traces, no SQL statements, no `SQLState` values, no
database constraint/table/column names, no Hibernate messages, no
filesystem paths, no credentials or environment variables. Implemented via
one centralized `@RestControllerAdvice` (`common.GlobalExceptionHandler`)
that every controller shares — see `docs/ARCHITECTURE.md`'s "Error
Handling" section for the full failure-to-status mapping and the reasoning
behind each one.

**Status codes used across all endpoints:**
- `400 Bad Request` — request-shape problems: missing/blank/malformed
  request-body fields, unsupported monetary precision or scale, malformed
  JSON syntax, an unrecognized (protected or unknown) JSON property, a
  malformed path UUID, or malformed/out-of-bounds pagination parameters.
- `404 Not Found` — the referenced account doesn't exist, is a `SYSTEM`
  account, or has an incompatible taxonomy for the endpoint (see each
  endpoint's own section above for exactly which cases apply).
- `409 Conflict` — an `Idempotency-Key` was reused for a command that
  doesn't canonically match the one it was first claimed for, including
  reuse across the deposit and transfer endpoints (Task 10 only —
  deposits and transfers).
- `422 Unprocessable Content` — a well-formed request that fails a domain
  rule: unsupported/mismatched currency, insufficient funds, or
  source == destination on a transfer.
- `500 Internal Server Error` — anything unexpected, including a
  persistence-layer failure not caught by earlier validation. The response
  body still uses the same envelope, with a generic, safe `message`; the
  original exception is logged server-side only, never returned to the
  client.

## Currently Available Endpoints

- `POST /api/v1/accounts` (Task 3) — see above.
- `POST /api/v1/accounts/{id}/deposits` (Task 4) — see above.
- `POST /api/v1/transfers` (Task 5) — see above.
- `GET /api/v1/accounts/{id}/balance` (Task 6) — see above.
- `GET /api/v1/accounts/{id}/transactions` (Task 6) — see above.
- `POST /api/v1/settlement-imports` (Task 14) — see above.
- `GET /v3/api-docs` and `GET /swagger-ui/index.html` (Task 8) — see
  "OpenAPI/Swagger" below.
- Spring Boot Actuator's built-in health check:

```
GET /actuator/health
```

## OpenAPI/Swagger (implemented, Task 8)

Generated by `springdoc-openapi-starter-webmvc-ui:3.0.2` directly from the
controllers, DTOs, and Jakarta Validation annotations documented above —
not a hand-maintained static file, so it cannot silently drift from the
actual request/response shapes.

- **OpenAPI document:** `GET /v3/api-docs` — OpenAPI 3.1 JSON.
- **Swagger UI:** `GET /swagger-ui/index.html` (the conventional
  `GET /swagger-ui.html` short form also redirects there).

Documents exactly the six endpoints above — `POST /api/v1/accounts`,
`POST /api/v1/accounts/{id}/deposits`, `POST /api/v1/transfers`,
`GET /api/v1/accounts/{id}/balance`, `GET /api/v1/accounts/{id}/transactions`,
`POST /api/v1/settlement-imports` — with their exact request/response schemas, documented status codes, and
the shared `ApiError` envelope reused across every error response. No
security scheme is declared (Phase 1 has no authentication — declaring one
would falsely imply these endpoints are protected). No `EXTERNAL_FUNDING`
or other `SYSTEM` account is exposed as something a client can act on — no
request field, no path, no example account id.

**API metadata:**
- Title: `LedgerGuard API`
- Description: "LedgerGuard is an atomic double-entry ledger API
  supporting customer-wallet creation, USD deposits, USD
  customer-to-customer transfers, materialized balance reads, and
  immutable transaction-history reads."
- Version: `0.0.1-SNAPSHOT` (mirrors `pom.xml`'s actual project version)
- No license, contact, or server URL — none of these are documented
  anywhere in this project, and publishing placeholder values for them was
  explicitly out of scope for Task 8. Omitting `servers` lets Swagger UI
  default to whatever host actually served the document, rather than a
  hardcoded value that could be wrong in a different environment.
