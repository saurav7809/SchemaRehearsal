\set ON_ERROR_STOP on
CREATE ROLE sr_app LOGIN;
CREATE ROLE sr_broker LOGIN CREATEDB CONNECTION LIMIT 3;
CREATE ROLE sr_worker LOGIN CONNECTION LIMIT 4;
CREATE ROLE sr_reader LOGIN CONNECTION LIMIT 3;
\password sr_app
\password sr_broker
\password sr_worker
\password sr_reader
ALTER ROLE sr_worker SET statement_timeout = '15s';
ALTER ROLE sr_worker SET lock_timeout = '3s';
ALTER ROLE sr_worker SET transaction_timeout = '35s';
ALTER ROLE sr_worker SET temp_file_limit = '32MB';
ALTER ROLE sr_reader SET default_transaction_read_only = on;
ALTER ROLE sr_reader SET statement_timeout = '5s';
CREATE DATABASE sr_metadata OWNER sr_app;
REVOKE ALL ON DATABASE sr_metadata FROM PUBLIC;
CREATE DATABASE sr_control OWNER sr_broker;
REVOKE ALL ON DATABASE sr_control FROM PUBLIC;
CREATE DATABASE sr_fixture OWNER sr_broker TEMPLATE template0;
REVOKE ALL ON DATABASE sr_fixture FROM PUBLIC;
GRANT CONNECT ON DATABASE sr_fixture TO sr_reader;
\connect sr_fixture
GRANT USAGE, CREATE ON SCHEMA public TO sr_worker;
GRANT USAGE ON SCHEMA public TO sr_reader;
SET ROLE sr_worker;
CREATE TABLE users (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, email text NOT NULL, display_name text, external_id text);
CREATE TABLE orders (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id bigint NOT NULL REFERENCES users, total_cents integer NOT NULL);
INSERT INTO users (email, display_name, external_id) VALUES ('ava@example.test', 'Ava', '42'), ('sam@example.test', NULL, 'legacy-A');
INSERT INTO orders (user_id, total_cents) VALUES (1, 2499), (2, 1200);
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sr_reader;
RESET ROLE;