# Supabase database audit — 2026-11-01

Both `dev` and `qa` exist. Their runtime roles can access their own schema and cannot use the other schema. No production schema was requested or checked.

| Table | dev | qa |
| --- | --- | --- |
| users | Present | Present |
| records | Present | Present |
| refresh_sessions | Present | Present |
| login_attempts | Present | Present |
| alembic_version | Present | Present |
| bank_accounts | Missing | Missing |
| categories | Missing | Missing |
| transactions | Missing | Missing |
| budgets | Missing | Missing |
| split_groups | Missing | Missing |
| split_members | Missing | Missing |
| split_shares | Missing | Missing |
| sync_log | Missing | Missing |

The five existing tables satisfy the current runnable implementation, which stores typed financial payloads in `records`. The eight missing tables belong to the original normalized relational plan. This difference is tracked in `BUILD_STATUS.md`.

## Add the planned tables

Run `backend/sql/complete_planned_tables.sql` in Supabase SQL Editor as `postgres`. It applies to **both schemas in one transaction** and does not create a production schema. It adds the missing tables, foreign keys and ownership constraints, indexes, updated-at triggers, six default categories per schema, and appropriate runtime-role grants. It also adds the planned `users.created_at` column without changing existing user IDs.

Current `users.id` is `varchar(36)`, so references to existing users use that type; the new financial entity primary keys use PostgreSQL UUIDs. Text fields with CHECK constraints enforce the planned enumeration values. Existing app tables and financial records are preserved.

The script was executed twice within one transaction against the actual project and **rolled back**. Both schemas contained all 13 tables during validation, with no duplicate seed categories after rerunning. No planned tables were permanently added by this audit. The script is suitable for this observed database state; `CREATE TABLE IF NOT EXISTS` does not repair unrelated pre-existing table definitions.

After applying it, use the final SELECT in the SQL file or refresh Supabase Table Editor and select `dev`/`qa` to see the 13 tables in each schema. Keep these schemas out of the exposed Data API schemas.

**Creating the tables does not migrate JSON payloads out of `records` or change which tables the Android/FastAPI code uses.** That requires a separate reviewed data migration and backend change. Do not delete `records` or the existing auth tables.
