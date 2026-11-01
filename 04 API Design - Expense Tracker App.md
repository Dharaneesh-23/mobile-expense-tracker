# Expense Tracker App — API Design

FastAPI, versioned under `/api/v1`. Every endpoint except `/auth/*` requires a Bearer access token; all data is scoped to the authenticated user server-side — no user can read or write another user's rows.

## 1. Auth flow

Self-rolled JWT: a short-lived access token (\~15 min) and a longer-lived refresh token (\~30 days), issued on login/register and rotated on refresh. Passwords hashed with bcrypt via passlib; tokens signed with python-jose.

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/auth/register` | POST | `{email, password}` | `{access_token, refresh_token}` |
| `/auth/login` | POST | `{email, password}` | `{access_token, refresh_token}` |
| `/auth/refresh` | POST | `{refresh_token}` | `{access_token}` |
| `/auth/logout` | POST | `{refresh_token}` | `204` (invalidates the refresh token) |

## 2. Bank accounts

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/bank-accounts` | GET | — | list of accounts |
| `/bank-accounts` | POST | `{bank_name, account_last4}` | created account |
| `/bank-accounts/{id}` | DELETE | — | `204` |

## 3. Categories

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/categories` | GET | — | system defaults + user's custom ones |
| `/categories` | POST | `{name}` | created category |
| `/categories/{id}` | PATCH | `{name}` | updated category |
| `/categories/{id}` | DELETE | — | `204` (blocked if transactions still reference it) |

## 4. Transactions

| Endpoint | Method | Body / Query | Response |
| --- | --- | --- | --- |
| `/transactions` | GET | `bank_account_id, category_id, direction, payee, date_from, date_to, amount_min, amount_max, page, limit` | paginated list, matches every filter from the requirements doc |
| `/transactions` | POST | `{amount, direction, payee_name, category_id, bank_account_id, occurred_at, note}` | created (manual entry) |
| `/transactions/{id}` | PATCH | any editable field | updated (corrections) |
| `/transactions/{id}` | DELETE | — | `204` |
| `/transactions/import/takeout` | POST | multipart file (Google Takeout export) | `{imported_count, duplicate_count}` |
| `/transactions/import/statement` | POST | multipart file + optional `{pdf_password}` | `{imported_count, duplicate_count}` |

## 5. Budgets

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/budgets` | GET | — | list with current spend vs limit per category |
| `/budgets` | POST | `{category_id, limit_amount, period, start_date}` | created |
| `/budgets/{id}` | PATCH | any editable field | updated |
| `/budgets/{id}` | DELETE | — | `204` |

## 6. Splits

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/splits/groups` | GET | — | list of groups |
| `/splits/groups` | POST | `{name}` | created group |
| `/splits/groups/{id}/members` | POST | `{name, phone}` | created member |
| `/splits/shares` | POST | `{group_id, member_id, amount, direction, linked_transaction_id}` | created share |
| `/splits/shares/{id}` | PATCH | `{status: "settled", settled_transaction_id}` | updated share |
| `/splits` | GET | `status, group_id` | all pending/settled splits, matching the Splits screen |

## 7. Reports

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/reports/pdf` | POST | `{date_from, date_to}` | streamed PDF (ReportLab-generated) |

## 8. Sync

| Endpoint | Method | Body | Response |
| --- | --- | --- | --- |
| `/sync` | POST | `{device_id, last_synced_at, changes: [...]}` | `{server_changes: [...], synced_at}` — two-way: pushes local changes, returns anything newer on the server since `last_synced_at` |

## 9. Budget alert trigger

Not a client-called endpoint: server-side logic runs on every transaction write (manual, import, or sync). If a transaction pushes a category's spend past its budget limit, FastAPI calls Firebase Cloud Messaging directly to push an alert to the user's device(s).
