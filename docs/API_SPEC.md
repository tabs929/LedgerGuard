# API Specification

> **Status: planning document.** None of the endpoints below exist yet. No
> controllers, services, or DTOs have been written. This describes the
> approved Phase 1 API contracts that Tasks 3–7 will implement.

All endpoints are versioned under `/api/v1` and are unauthenticated in
Phase 1 (authentication is a Phase 3 concern).

## POST /api/v1/accounts (not implemented)

Request: `{ "ownerName": string, "currency": "USD" }`

Response 201: `{ "id": uuid, "ownerName": string, "currency": string, "balance": "0.0000", "createdAt": iso8601 }`

Errors: 422 unsupported currency (only `USD` is accepted in Phase 1).

## GET /api/v1/accounts/{id} (not implemented)

Response 200: same shape as account creation. Never returns a `SYSTEM`
account.

Response 404: not found — including when `{id}` is a `SYSTEM` account, which
is treated identically to a nonexistent id (see `docs/ARCHITECTURE.md` on
public vs. internal account lookup).

## POST /api/v1/accounts/{id}/deposits (not implemented)

Request: `{ "amount": "100.00", "currency": "USD" }`

Response 201: `{ "transactionId": uuid, "accountId": uuid, "amount": "100.00", "currency": "USD", "newBalance": "100.00", "createdAt": iso8601 }`

Errors: 400 validation (amount <= 0), 404 account not found (including a
`SYSTEM` account id), 422 currency mismatch.

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

The only endpoint that actually exists today is Spring Boot Actuator's
built-in health check:

```
GET /actuator/health
```

## OpenAPI/Swagger

Not yet integrated (planned for Task 8). No `/swagger-ui` or `/v3/api-docs`
endpoint exists yet.
