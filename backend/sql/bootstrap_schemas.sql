-- Run once as the Supabase administrator in SQL Editor.
-- Passwords are intentionally absent. Set LOGIN role passwords separately.
-- No production schema is created during the personal-use development phase.
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'expense_dev_owner') THEN CREATE ROLE expense_dev_owner NOLOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'expense_qa_owner') THEN CREATE ROLE expense_qa_owner NOLOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'expense_dev_app') THEN CREATE ROLE expense_dev_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'expense_qa_app') THEN CREATE ROLE expense_qa_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF;
END $$;
GRANT expense_dev_owner, expense_qa_owner TO postgres;

CREATE SCHEMA IF NOT EXISTS dev AUTHORIZATION expense_dev_owner;
CREATE SCHEMA IF NOT EXISTS qa AUTHORIZATION expense_qa_owner;
REVOKE ALL ON SCHEMA dev, qa FROM PUBLIC, anon, authenticated;
REVOKE ALL ON SCHEMA qa FROM expense_dev_app;
REVOKE ALL ON SCHEMA dev FROM expense_qa_app;
GRANT USAGE ON SCHEMA dev TO expense_dev_app;
GRANT USAGE ON SCHEMA qa TO expense_qa_app;
ALTER ROLE expense_dev_app SET search_path = dev, pg_catalog;
ALTER ROLE expense_qa_app SET search_path = qa, pg_catalog;

-- Migrations run as postgres, not as the runtime role. Apply grants to its future objects.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA dev GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO expense_dev_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA qa GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO expense_qa_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA dev GRANT USAGE, SELECT ON SEQUENCES TO expense_dev_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA qa GRANT USAGE, SELECT ON SEQUENCES TO expense_qa_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA dev REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA qa REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dev TO expense_dev_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA qa TO expense_qa_app;

-- Keep dev/qa out of Supabase's exposed API schemas. Android talks only to FastAPI.
-- After migration, verify each role cannot SELECT from the other schema.
