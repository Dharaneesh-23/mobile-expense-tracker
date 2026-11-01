-- Run as postgres in Supabase SQL Editor after the app's Alembic 0001 migration.
-- Adds the original relational design to BOTH dev and qa. Does not create prod.
-- Preserves users, records, refresh_sessions, login_attempts, and existing data.
-- The current app still uses records; this script does NOT migrate its payloads.
-- user_id is varchar(36) to match the current users.id; new entity IDs are UUIDs.
BEGIN;

DO $setup$
DECLARE
    env text;
    tbl text;
    app_role text;
BEGIN
    FOREACH env IN ARRAY ARRAY['dev', 'qa'] LOOP
        app_role := 'expense_' || env || '_app';
        IF to_regclass(format('%I.users', env)) IS NULL THEN
            RAISE EXCEPTION 'Missing %.users: apply the app migration first', env;
        END IF;

        EXECUTE format('ALTER TABLE %I.users ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now()', env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.bank_accounts (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                bank_name text NOT NULL CHECK (length(trim(bank_name)) > 0),
                account_last4 varchar(4) NOT NULL CHECK (account_last4 ~ '^[0-9]{4}$'),
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                UNIQUE (user_id, id),
                UNIQUE (user_id, bank_name, account_last4)
            )
        $ddl$, env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.categories (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) REFERENCES %1$I.users(id),
                name text NOT NULL CHECK (length(trim(name)) > 0),
                is_system boolean NOT NULL DEFAULT false,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                CHECK ((user_id IS NULL AND is_system) OR (user_id IS NOT NULL AND NOT is_system))
            )
        $ddl$, env);
        EXECUTE format('CREATE UNIQUE INDEX IF NOT EXISTS categories_system_name ON %I.categories (lower(name)) WHERE user_id IS NULL', env);
        EXECUTE format('CREATE UNIQUE INDEX IF NOT EXISTS categories_user_name ON %I.categories (user_id, lower(name)) WHERE user_id IS NOT NULL', env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.transactions (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                bank_account_id uuid,
                category_id uuid REFERENCES %1$I.categories(id),
                source_type text NOT NULL CHECK (source_type IN ('notification','sms','manual','import_takeout','import_statement')),
                source_app text CHECK (source_app IN ('gpay','phonepe','paytm','slice','supermoney','bank_sms','manual')),
                amount numeric(12,2) NOT NULL CHECK (amount > 0),
                direction text NOT NULL CHECK (direction IN ('credit','debit')),
                payee_name text NOT NULL,
                raw_text text,
                occurred_at timestamptz NOT NULL,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                duplicate_of uuid,
                note text,
                reference text,
                UNIQUE (user_id, id),
                FOREIGN KEY (user_id, bank_account_id) REFERENCES %1$I.bank_accounts(user_id, id),
                FOREIGN KEY (user_id, duplicate_of) REFERENCES %1$I.transactions(user_id, id),
                CHECK (duplicate_of IS NULL OR duplicate_of <> id)
            )
        $ddl$, env);
        EXECUTE format('CREATE INDEX IF NOT EXISTS transactions_user_date ON %I.transactions (user_id, occurred_at DESC)', env);
        EXECUTE format('CREATE INDEX IF NOT EXISTS transactions_user_reference ON %I.transactions (user_id, reference) WHERE reference IS NOT NULL', env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.budgets (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                category_id uuid NOT NULL REFERENCES %1$I.categories(id),
                limit_amount numeric(12,2) NOT NULL CHECK (limit_amount > 0),
                period text NOT NULL CHECK (period IN ('weekly','monthly','custom')),
                start_date date NOT NULL,
                end_date date,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                UNIQUE (user_id, category_id, period, start_date),
                CHECK (end_date IS NULL OR end_date >= start_date)
            )
        $ddl$, env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.split_groups (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                name text NOT NULL CHECK (length(trim(name)) > 0),
                source text NOT NULL CHECK (source IN ('app_native','gpay_split','takeout_import')),
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                UNIQUE (user_id, id)
            )
        $ddl$, env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.split_members (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                group_id uuid NOT NULL,
                name text NOT NULL CHECK (length(trim(name)) > 0),
                phone text,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                FOREIGN KEY (user_id, group_id) REFERENCES %1$I.split_groups(user_id, id),
                UNIQUE (user_id, group_id, id)
            )
        $ddl$, env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.split_shares (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                group_id uuid NOT NULL,
                member_id uuid NOT NULL,
                linked_transaction_id uuid,
                amount numeric(12,2) NOT NULL CHECK (amount > 0),
                direction text NOT NULL CHECK (direction IN ('they_owe_you','you_owe_them')),
                status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','settled')),
                settled_at timestamptz,
                settled_transaction_id uuid,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                deleted boolean NOT NULL DEFAULT false,
                FOREIGN KEY (user_id, group_id) REFERENCES %1$I.split_groups(user_id, id),
                FOREIGN KEY (user_id, group_id, member_id) REFERENCES %1$I.split_members(user_id, group_id, id),
                FOREIGN KEY (user_id, linked_transaction_id) REFERENCES %1$I.transactions(user_id, id),
                FOREIGN KEY (user_id, settled_transaction_id) REFERENCES %1$I.transactions(user_id, id)
            )
        $ddl$, env);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %1$I.sync_log (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id varchar(36) NOT NULL REFERENCES %1$I.users(id),
                device_id text NOT NULL,
                last_synced_at timestamptz,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),
                UNIQUE (user_id, device_id)
            )
        $ddl$, env);

        -- A category may belong to this user or be a shared system category.
        EXECUTE format($ddl$
            CREATE OR REPLACE FUNCTION %1$I.expense_validate_category_owner()
            RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$
            BEGIN
                IF NEW.category_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM %1$I.categories c
                    WHERE c.id = NEW.category_id
                      AND (c.user_id = NEW.user_id OR (c.user_id IS NULL AND c.is_system))
                ) THEN
                    RAISE EXCEPTION 'Category does not belong to this user';
                END IF;
                RETURN NEW;
            END
            $fn$
        $ddl$, env);

        EXECUTE format($ddl$
            CREATE OR REPLACE FUNCTION %1$I.expense_touch_updated_at()
            RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$
            BEGIN NEW.updated_at = now(); RETURN NEW; END
            $fn$
        $ddl$, env);

        FOREACH tbl IN ARRAY ARRAY['bank_accounts','categories','transactions','budgets','split_groups','split_members','split_shares','sync_log'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = to_regclass(format('%I.%I',env,tbl)) AND tgname = 'expense_touch_updated_at') THEN
                EXECUTE format('CREATE TRIGGER expense_touch_updated_at BEFORE UPDATE ON %1$I.%2$I FOR EACH ROW EXECUTE FUNCTION %1$I.expense_touch_updated_at()', env, tbl);
            END IF;
            EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM PUBLIC, anon, authenticated', env, tbl);
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I.%I TO %I', env, tbl, app_role);
        END LOOP;
        FOREACH tbl IN ARRAY ARRAY['transactions','budgets'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = to_regclass(format('%I.%I',env,tbl)) AND tgname = 'expense_validate_category_owner') THEN
                EXECUTE format('CREATE TRIGGER expense_validate_category_owner BEFORE INSERT OR UPDATE ON %1$I.%2$I FOR EACH ROW EXECUTE FUNCTION %1$I.expense_validate_category_owner()', env, tbl);
            END IF;
        END LOOP;

        EXECUTE format('REVOKE ALL ON FUNCTION %I.expense_touch_updated_at() FROM PUBLIC', env);
        EXECUTE format('REVOKE ALL ON FUNCTION %I.expense_validate_category_owner() FROM PUBLIC', env);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %I.expense_touch_updated_at() TO %I', env, app_role);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %I.expense_validate_category_owner() TO %I', env, app_role);

        EXECUTE format($seed$
            INSERT INTO %1$I.categories (name, is_system)
            SELECT v.name, true FROM (VALUES ('Food'),('Travel'),('Bills'),('Shopping'),('Entertainment'),('Other')) v(name)
            WHERE NOT EXISTS (SELECT 1 FROM %1$I.categories c WHERE c.user_id IS NULL AND lower(c.name) = lower(v.name))
            ON CONFLICT DO NOTHING
        $seed$, env);
    END LOOP;
END
$setup$;

-- Check the result in Supabase; each schema should now have 13 tables.
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('dev', 'qa') AND table_type = 'BASE TABLE'
ORDER BY table_schema, table_name;

COMMIT;
