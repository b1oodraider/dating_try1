# Техническая поддержка (support tickets)

A user opens a ticket with a topic and a first message, then writes in the same thread. Staff answer from the
back office. Staff are accounts with the role `SUPPORT`, `MODERATOR` or `ADMIN`.
Code: `com.dating.support`. Schema: changeset `030-support.yaml`.

## Statuses

| Status     | Meaning                         | Set when                                              |
|------------|---------------------------------|-------------------------------------------------------|
| `OPEN`     | Waits for staff                 | the ticket is created; the user writes (also on ANSWERED / CLOSED) |
| `ANSWERED` | Staff replied last              | staff reply (on any status)                           |
| `CLOSED`   | Done                            | staff close it (idempotent)                           |

Categories (`category`): `ACCOUNT`, `PROFILE`, `LIKES_MATCHES`, `PAYMENTS`, `SAFETY`, `BUG`, `OTHER`.

## Shapes

`SupportMessage`: `{ id: uuid, fromStaff: boolean, text: string, createdAt: date-time }`.
Users never see which staff member wrote a message.

`TicketView` (user): `{ id, category, status, createdAt, updatedAt, lastMessageAt, messages: SupportMessage[] }`
(messages oldest first).

`TicketSummary` (user list): `{ id, category, status, createdAt, updatedAt, lastMessageAt }`.

`StaffQueueItem`: `{ id, accountId, profileId | null, displayName | null, testAccount, category, status, createdAt,
lastMessageAt, preview | null }`. `preview` is the first 200 characters of the first message.

`StaffQueuePage`: `{ items: StaffQueueItem[], page, limit, totalElements }`.

`StaffTicketView`: `{ id, accountId, phoneMasked, profileId | null, displayName | null, testAccount, category, status,
createdAt, updatedAt, lastMessageAt, messages: SupportMessage[] }`. `profileId` / `displayName` are null when the
account has no profile.

## User endpoints (Bearer token)

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/api/v1/support/tickets` | `{ category, text }` | 201 `TicketView` |
| GET  | `/api/v1/support/tickets` | — | 200 `TicketSummary[]`, newest activity (`lastMessageAt`) first |
| GET  | `/api/v1/support/tickets/{id}` | — | 200 `TicketView` |
| POST | `/api/v1/support/tickets/{id}/messages` | `{ text }` | 200 `TicketView`, status becomes `OPEN` |

`text`: required, not blank, at most 4000 characters, stored trimmed.

Errors:
- 400 `VALIDATION_ERROR` (`fieldErrors.category` / `fieldErrors.text`), 400 `MALFORMED_REQUEST` (unknown category);
- 404 `TICKET_NOT_FOUND`: no such ticket, or it belongs to someone else;
- 429 `RATE_LIMITED` with `Retry-After`: more than 5 new tickets per account in 24 hours (counted in the DB).
  Messages in existing tickets are not limited.

## Staff endpoints (SUPPORT, MODERATOR or ADMIN; otherwise 403 `ROLE_REQUIRED`, `details.roles`)

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET  | `/api/v1/admin/support/tickets?status=OPEN\|ANSWERED\|CLOSED&page=0&limit=20` | — | 200 `StaffQueuePage` |
| GET  | `/api/v1/admin/support/tickets/{id}` | — | 200 `StaffTicketView` |
| POST | `/api/v1/admin/support/tickets/{id}/messages` | `{ text }` | 200 `StaffTicketView`, status becomes `ANSWERED` |
| POST | `/api/v1/admin/support/tickets/{id}/close` | — | 200 `StaffTicketView`, status `CLOSED` |

- `status` defaults to `OPEN`; an unknown value gives 400 `VALIDATION_ERROR` (`fieldErrors.status`).
- `limit` 1..100 (out of range: 20), `page` from 0.
- Sort order: `OPEN` has the longest-waiting ticket first (`lastMessageAt` ascending). `ANSWERED` and `CLOSED` have
  the latest activity first.
- 404 `TICKET_NOT_FOUND`.
- Audit log (`/api/v1/admin/audit`): entity type `SUPPORT_TICKET`, actions `REPLY` (before `{status}`, after
  `{status, messageId}`; no message text) and `CLOSE` (before/after `{status}`). Closing a ticket that is
  already closed is not logged.

## Push on a staff reply

Sent after the transaction commits, to every device of the ticket owner:
title «Ответ поддержки», body «Поддержка ответила на ваше обращение»,
data `{ type: "support_reply", ticketId }`. The push never contains the message text.

## Account deletion

On `AccountDeletedEvent` the account's tickets and every message in them are deleted. Staff messages in other
users' tickets stay; the account row is only anonymized.

## Role

`SUPPORT` is a new staff role (`PUT /api/v1/admin/accounts/{id}/roles/SUPPORT`, ADMIN only). It is also listed in
`GET /api/v1/admin/roles` → `available` and accepted by the testing API `/api/v1/testing/roles/SUPPORT`.
SUPPORT gives access to support tickets only. The web cabinet shows a SUPPORT-only account just the «Поддержка»
section, without the link to the account card (the account endpoints need MODERATOR or ADMIN).
