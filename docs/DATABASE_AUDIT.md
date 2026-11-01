# Supabase database audit — 2026-11-01

Both `dev` and `qa` exist. Their runtime roles can access their own schema and cannot use the other schema. After the user applied the SQL and migration `0002` was run, all 13 tables below were verified present in both environments. No production schema was requested or created.

| Table | dev | qa |
| --- | --- | --- |
| users | Present | Present |
| records | Present | Present |
| refresh_sessions | Present | Present |
| login_attempts | Present | Present |
| alembic_version | Present | Present |
| bank_accounts | Present | Present |
| categories | Present | Present |
| transactions | Present | Present |
| budgets | Present | Present |
| split_groups | Present | Present |
| split_members | Present | Present |
| split_shares | Present | Present |
| sync_log | Present | Present |

The initial audit found only the five journal/auth/migration tables. The eight relational tables have since been applied and connected to FastAPI's sync path. The current implementation is tracked in `BUILD_STATUS.md`.

## Add the planned tables

Run `backend/sql/complete_planned_tables.sql` in Supabase SQL Editor as `postgres`. It applies to **both schemas in one transaction** and does not create a production schema. It adds the missing tables, foreign keys and ownership constraints, indexes, updated-at triggers, six default categories per schema, and appropriate runtime-role grants. It also adds the planned `users.created_at` column without changing existing user IDs.

Current `users.id` is `varchar(36)`, so references to existing users use that type; the new financial entity primary keys use PostgreSQL UUIDs. Text fields with CHECK constraints enforce the planned enumeration values. Existing app tables and financial records are preserved.

Before user application, the script was executed twice in one transaction and rolled back to validate it. The user then applied it, and migration `0002` added sync identity links and an active-budget uniqueness index that permits replacing deleted limits. All 13 tables are now permanently present in both schemas. `CREATE TABLE IF NOT EXISTS` does not repair unrelated pre-existing table definitions.

After applying it, use the final SELECT in the SQL file or refresh Supabase Table Editor and select `dev`/`qa` to see the 13 tables in each schema. Keep these schemas out of the exposed Data API schemas.

The backend change and journal-preserving backfill are now implemented and applied. New sync writes update both relational entities and the journal atomically. There were no cloud transaction/budget/split journal entries to move at backfill time. `records` remains the revision/tombstone compatibility journal; do not delete it or the auth tables. Relational transaction reads and report generation are live-tested.
