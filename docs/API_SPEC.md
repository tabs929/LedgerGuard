# API Specification

> **Status: account creation (Task 3), deposits (Task 4), transfers
> (Task 5), and account balance/transaction-history reads (Task 6)
> implemented; the rest is still a planning document.** `POST
> /api/v1/accounts`, `POST /api/v1/accounts/{id}/deposits`, `POST
> /api/v1/transfers`, `GET /api/v1/accounts/{id}/balance`, and `GET
> /api/v1/accounts/{id}/transactions` all exist and match the contracts
> below exactly. Both deposits and transfers are USD-only and always post
> a balanced double-entry ledger transaction in the same database
> transaction as the materialized balance updates; the two read endpoints
> never modify that state. Plain `GET /api/v1/accounts/{id}` remains
> unimplemented — `docs/TASKS.md`'s Task 6 line scopes that task to
> balance and history only, not general account lookup — no controller,
> service, or DTO exists for it yet.

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

## POST /api/v1/accounts/{id}/deposits (implemented, Task 4)

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
- 400 validation — missing/non-positive/malformed `amount`, an amount with
  unsupported precision or scale, missing or malformed `currency`, or an
  unrecognized JSON property.
- 404 account not found — including when `{id}` is a `SYSTEM` account
  (treated identically to a nonexistent id) or any account that is not a
  `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`.
- 422 currency mismatch — `currency` is a well-formed but non-`USD` code,
  or (in principle) the destination account's own currency is not `USD`.

## POST /api/v1/transfers (implemented, Task 5)

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
- 400 validation — missing/non-positive/malformed `amount`, an amount with
  unsupported precision or scale, missing source/destination id, missing
  or malformed `currency`, or an unrecognized JSON property.
- 404 account not found — either id being a `SYSTEM` account (treated
  identically to a nonexistent id) or any account that is not a
  `CUSTOMER`/`LIABILITY`/`CUSTOMER_WALLET`.
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

## Error Response Shape

All error responses share one shape:

```json
{
  "timestamp": "iso8601",
  "status": 404,
  "error": "Not Found",
  "message": "...",
  "path": "/api/v1/accounts/..."
}
```

No internal exception details (stack traces, SQL state) are ever exposed.
Implemented via a global `@ControllerAdvice` (planned for Task 7).

## Currently Available Endpoints

- `POST /api/v1/accounts` (Task 3) — see above.
- `POST /api/v1/accounts/{id}/deposits` (Task 4) — see above.
- `POST /api/v1/transfers` (Task 5) — see above.
- `GET /api/v1/accounts/{id}/balance` (Task 6) — see above.
- `GET /api/v1/accounts/{id}/transactions` (Task 6) — see above.
- Spring Boot Actuator's built-in health check:

```
GET /actuator/health
```

## OpenAPI/Swagger

Not yet integrated (planned for Task 8). No `/swagger-ui` or `/v3/api-docs`
endpoint exists yet.
