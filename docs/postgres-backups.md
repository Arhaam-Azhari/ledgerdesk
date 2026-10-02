# PostgreSQL backup and restore

This workflow uses PostgreSQL's native custom-format archives. It backs up one application database and restores it into a newly created database. It retains the ledger, documents, receipt bytes, user password hashes, memberships, activity, migration history and saved command keys. It does not back up server roles, connection secrets or application binaries.

## Preparation

Install PostgreSQL client tools (`pg_dump`, `pg_restore`, `createdb`) matching the server major version; this milestone tests PostgreSQL 17. Use the normal external connection configuration, for example `PGHOST`, `PGPORT`, `PGUSER` and a protected `.pgpass`/`PGPASSFILE`. Do not put passwords in connection strings or shell commands. The tool accepts a plain database name, not a credential-bearing URL.

Stop Ledgerdesk and other writers for this recovery workflow. `pg_dump` provides a consistent database snapshot, but stopping application writes makes the cutoff explicit for operator comparison. `--confirm-stopped` records your confirmation; it does not shut down the application or prove there are no writers. Ensure the database user can read all application objects and create a new database. Keep the same backend version and deployment configuration available separately.

## Create the archive

From the repository root:

```sh
mkdir -p /path/to/private-backups
python3 scripts/postgres_backup.py backup ledgerdesk /path/to/private-backups/ledgerdesk-pg-2026-10-02 --confirm-stopped
```

The destination must be new. A completed folder contains `database.dump` and a size/SHA-256 `manifest.json`. Keep both together, restrict access, and copy completed folders to separate storage. The archive contains private records and password hashes. Files receive owner-only permissions where supported, but this tool does not encrypt them. Checksums detect accidental changes; they do not authenticate a maliciously replaced archive and manifest. Restore only trusted backups.

## Restore to a new database

```sh
python3 scripts/postgres_backup.py restore /path/to/private-backups/ledgerdesk-pg-2026-10-02 ledgerdesk_restored --confirm-stopped
```

The tool checks the manifest/checksum and inspects the archive before creating anything on the server. `createdb` refuses an existing name. Restore uses one transaction and exits on error; it does not clean or drop existing objects. Object owners and grants are not copied: the connected restore user owns the restored objects. Review application permissions separately before deployment.

If restore fails after database creation, the new database remains for inspection. Do not point the application at it or assume the command succeeded. Choose another new name for a subsequent attempt, or have the operator deliberately remove the failed target after inspecting it. The tool never drops databases automatically.

Point the backend's external `DATABASE_URL` to `jdbc:postgresql://host:5432/ledgerdesk_restored`, with the appropriate `DATABASE_USER` and `DATABASE_PASSWORD`. Keep the original stopped and preserve the same configured/persistent account mode. Persistent accounts use passwords stored at the backup cutoff; startup values do not replace them. Compare dated reports, trial balance, documents, receipts and activity before resuming work. An older backup omits later transactions and password changes; plan those differences explicitly.

## Verification

Eight unit tests cover archive/manifest creation, restore ordering, corrupt archives, failed dumps, existing backup/database protection, database-name validation, manifest paths and keeping passwords out of command arguments. Together with the six H2 tests, run `python3 -m unittest discover -s scripts -p 'test_*.py'`.

A dedicated CI job uses PostgreSQL 17 and its matching native tools inside the disposable service container. It starts the packaged backend against a fresh source database, records a supplier and $125.37 owner contribution, stops the app, invokes the backup and restore CLI, and opens a separate restored database. It compares the full workspace, checks owner/reviewer authentication and reviewer write denial, retries the contribution without duplication, and verifies a second restore refuses the existing database. Source `9185dc4538a539928cd464c3b68464fd8c520ec3` passed [run 37079403182](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37079403182): all 14 backup-tool tests, 229 integration tests on each of H2 and PostgreSQL 17, production frontend build, all 20 Chromium workflows and the dedicated real PostgreSQL restore job. That job completed the native archive, separate-database restore, exact workspace comparison, stored owner/reviewer checks, blocked reviewer write, retained contribution retry and existing-target refusal. The fixture does not upload receipts or back up server roles.

This is a verified recovery path for one application database on the PostgreSQL 17 CI service. Scheduled retention, encryption, point-in-time recovery, power-loss recovery, role/grant provisioning and hosted authentication remain separate work.


The native tool behavior is described in the PostgreSQL 17 manuals for [pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html) and [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html). The application test above verifies this project's use of those tools; it does not establish every deployment configuration.
