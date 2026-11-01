# Expense Tracker App — Database Schema

PostgreSQL, managed by Supabase on the free tier. Financial entities use UUID primary keys. Existing user IDs and the sync journal retain their compatible string IDs; authenticated-user filtering and ownership foreign keys enforce data isolation.

Implementation status: both dev/qa now contain the relational tables below plus `records`, `refresh_sessions`, `login_attempts`, and Alembic metadata (13 tables per schema). Migration `0002` links transactions, budgets, and split shares to client IDs through `sync_record_id`; financial sync writes populate these tables atomically while retaining `records` as the revision/tombstone journal. Existing user IDs remain `varchar(36)` for compatibility; new financial entity IDs are UUIDs. See [database audit](docs/DATABASE_AUDIT.md) and [build status](docs/BUILD_STATUS.md).

## 1. `users`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| email | text, unique |  |
| password\_hash | text | bcrypt via passlib |
| created\_at | timestamptz |  |

## 2. `bank_accounts`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| user\_id | UUID, FK → users |  |
| bank\_name | text | e.g. "HDFC", "SBI" |
| account\_last4 | text | last 4 digits only, never the full number |
| created\_at | timestamptz |  |

## 3. `categories`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| user\_id | UUID, FK → users, nullable | null = system default category, shared across all users |
| name | text | e.g. "Food", "Travel", "Bills" |
| is\_system | boolean | true for the seeded default set |

## 4. `transactions`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| user\_id | UUID, FK → users |  |
| bank\_account\_id | UUID, FK → bank\_accounts, nullable |  |
| category\_id | UUID, FK → categories, nullable | null = Uncategorized |
| source\_type | enum | `notification` \| `sms` \| `manual` \| `import_takeout` \| `import_statement` |
| source\_app | enum, nullable | `gpay` \| `phonepe` \| `paytm` \| `slice` \| `supermoney` \| `bank_sms` \| `manual` |
| amount | numeric(12,2) |  |
| direction | enum | `credit` \| `debit` |
| payee\_name | text | as parsed from the source |
| raw\_text | text, nullable | original notification/SMS text, kept for re-parsing if a parser improves later |
| occurred\_at | timestamptz | when the transaction actually happened |
| created\_at | timestamptz | when the app captured/imported it |
| duplicate\_of | UUID, FK → transactions, nullable, self-referencing | set when dedup merges two captures of the same event |
| note | text, nullable | user-added note |

## 5. `budgets`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| user\_id | UUID, FK → users |  |
| category\_id | UUID, FK → categories |  |
| limit\_amount | numeric(12,2) |  |
| period | enum | `weekly` \| `monthly` \| `custom` |
| start\_date | date |  |
| created\_at | timestamptz |  |

## 6. `split_groups`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| user\_id | UUID, FK → users | the owning user |
| name | text | e.g. "Goa trip" |
| source | enum | `app_native` \| `gpay_split` \| `takeout_import` — how the group was created |
| created\_at | timestamptz |  |

## 7. `split_members`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| group\_id | UUID, FK → split\_groups |  |
| name | text | other people are tracked by name only, not app accounts |
| phone | text, nullable |  |

## 8. `split_shares`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| group\_id | UUID, FK → split\_groups |  |
| member\_id | UUID, FK → split\_members |  |
| linked\_transaction\_id | UUID, FK → transactions, nullable | the original payment this share was split from, if any |
| amount | numeric(12,2) | this member's share |
| direction | enum | `they_owe_you` \| `you_owe_them` |
| status | enum | `pending` \| `settled` |
| settled\_at | timestamptz, nullable |  |
| settled\_transaction\_id | UUID, FK → transactions, nullable | the incoming/outgoing payment that settled it, when auto-matched |
| created\_at | timestamptz |  |

## 9. `sync_log`

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID, PK |  |
| user\_id | UUID, FK → users |  |
| device\_id | text | identifies the Android device/install |
| last\_synced\_at | timestamptz | used by the sync worker to fetch only what changed since |

## 10. Relationships

- A `user` has many `bank_accounts`, `transactions`, `categories` (custom ones), `budgets`, `split_groups`, and one `sync_log` row per device
- A `transaction` optionally belongs to a `bank_account` and a `category`, and may reference another `transaction` as its duplicate
- A `split_group` has many `split_members`, each of whom has many `split_shares`
- A `split_share` optionally links to the `transaction` that created it (the original expense) and separately to the `transaction` that settled it (the repayment)
- Default `categories` (is\_system = true) are shared read-only rows every user sees; a user's custom categories are scoped to them via `user_id`
