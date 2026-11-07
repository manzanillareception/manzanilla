-- Apply after Alembic as the approved database/migration administrator.
-- Dedicated control-plane database only. No password or login is created here.
-- The trusted API has application-table access through explicit RLS policies;
-- it is NOT a database owner, superuser or BYPASSRLS role. HTTP tenant checks
-- remain authoritative for human/device access.
BEGIN;

DO $role_setup$
DECLARE
    runtime_oid oid;
    runtime_name constant text := 'manzanilla_control_api';
    table_name text;
    browser_role text;
    control_tables constant text[] := ARRAY[
        'alerts', 'audit_events', 'auth_rate_buckets', 'command_receipts',
        'commands', 'config_revisions', 'device_boots', 'device_credentials',
        'device_events', 'devices', 'enrollments', 'hotels', 'memberships',
        'organizations', 'rate_buckets', 'users', 'web_sessions',
        'worker_leases', 'worker_progress'
    ];
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_name) THEN
        CREATE ROLE manzanilla_control_api NOLOGIN NOINHERIT NOSUPERUSER
            NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    SELECT oid INTO runtime_oid FROM pg_roles WHERE rolname = runtime_name;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE oid = runtime_oid
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Refusing unsafe pre-existing control runtime role';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = runtime_oid) THEN
        RAISE EXCEPTION 'Control runtime role must not inherit or SET ROLE into another role';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.member
        WHERE am.roleid = runtime_oid AND r.rolname IN ('anon', 'authenticated', 'authenticator')) THEN
        RAISE EXCEPTION 'Browser/Data API roles must not be members of the control runtime role';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'public' AND nspowner = runtime_oid) THEN
        RAISE EXCEPTION 'Control runtime role must not own its schema';
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), runtime_name);
    -- A negative role grant cannot override PUBLIC's CREATE grant, so remove
    -- the broad grant in this dedicated database before granting USAGE only.
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    REVOKE CREATE ON SCHEMA public FROM manzanilla_control_api;
    GRANT USAGE ON SCHEMA public TO manzanilla_control_api;
    IF has_database_privilege(runtime_name, current_database(), 'CREATE') THEN
        RAISE EXCEPTION 'Remove database CREATE privilege from the runtime role before setup';
    END IF;
    FOR table_name IN SELECT unnest(control_tables) LOOP
        IF to_regclass(format('public.%I', table_name)) IS NULL THEN
            RAISE EXCEPTION 'Apply all control migrations before role setup';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = table_name AND c.relowner = runtime_oid) THEN
            RAISE EXCEPTION 'Control runtime role must not own application tables';
        END IF;
        EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC', table_name);
        FOREACH browser_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = browser_role) THEN
                EXECUTE format('REVOKE ALL ON TABLE public.%I FROM %I', table_name, browser_role);
            END IF;
        END LOOP;
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO %I', table_name, runtime_name);
        EXECUTE format('DROP POLICY IF EXISTS control_api_runtime ON public.%I', table_name);
        EXECUTE format('CREATE POLICY control_api_runtime ON public.%I FOR ALL TO %I USING (true) WITH CHECK (true)',
                       table_name, runtime_name);
    END LOOP;
    REVOKE ALL ON TABLE public.alembic_version FROM PUBLIC;
    GRANT SELECT ON TABLE public.alembic_version TO manzanilla_control_api;
    FOREACH browser_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = browser_role) THEN
            EXECUTE format('REVOKE ALL ON TABLE public.alembic_version FROM %I', browser_role);
        END IF;
    END LOOP;
END
$role_setup$;

COMMIT;
