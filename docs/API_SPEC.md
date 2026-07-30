# API Specification

> **Status: account creation (Task 3) and deposits (Task 4) implemented;
> the rest is still a planning document.** `POST /api/v1/accounts` and
> `POST /api/v1/accounts/{id}/deposits` exist and match the contracts below
> exactly. Deposits are USD-only and always post a balanced double-entry
> ledger transaction (DEBIT `EXTERNAL_FUNDING`, CREDIT the customer wallet)
> in the same database transaction as the materialized balance updates.
> `GET /api/v1/accounts/{id}` and everything below it remain unimplemented —
> no controllers, services, or DTOs exist for them yet.

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

## POST /api/v1/transfers (not implemented)

Request: `{ "sourceAccountId": uuid, "destinationAccountId": uuid, "amount": "50.00", "currency": "USD" }`

Response 201: `{ "transactionId": uuid, "sourceAccountId": uuid, "destinationAccountId": uuid, "amount": "50.00", "currency": "USD", "createdAt": iso8601 }`

Errors: 400 validation (amount <= 0), 404 account not found (either id being
a `SYSTEM` account is treated identically to nonexistent), 422 insufficient
funds, 422 currency mismatch, 422 source == destination.

## GET /api/v1/accounts/{id}/balance (not implemented)

Response 200: `{ "accountId": uuid, "balance": "150.00", "currency": "USD" }`

Response 404: not found (including a `SYSTEM` account id).

## GET /api/v1/accounts/{id}/transactions?page=&size= (not implemented)

Response 200: paginated list of `{ "transactionId": uuid, "entryType": "DEBIT"|"CREDIT", "amount": string, "currency": string, "createdAt": iso8601 }`

Response 404: not found (including a `SYSTEM` account id).

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
- Spring Boot Actuator's built-in health check:

```
GET /actuator/health
```

## OpenAPI/Swagger

Not yet integrated (planned for Task 8). No `/swagger-ui` or `/v3/api-docs`
endpoint exists yet.
