# Local H2 backup and restore

The local demo stores its ledger, documents, receipt bytes, accounts, memberships and activity in the H2 database. This tool copies that database after a graceful shutdown and records its size and SHA-256 checksum. It restores into a new file, leaving the original installation available for comparison.

## Make a backup

Stop every backend or database tool using this file and wait for them to exit. From the repository root, with Python 3 installed:

```sh
mkdir -p /path/to/private-backups
python3 scripts/local_backup.py backup backend/demo-data/ledgerdesk.mv.db /path/to/private-backups/ledgerdesk-2026-10-02 --confirm-stopped
```

Use the actual `.mv.db` file if your datasource points elsewhere. The destination folder must be new. `--confirm-stopped` is the operator's confirmation, not an automatic shutdown: H2 may have no separate lock file while running. The tool rejects an observed lock, checks for changes during copying, and refuses an existing destination. These checks do not make copying a live database safe.

A completed folder contains `database.mv.db` and `manifest.json`. Keep the whole folder together and outside this repository, with access restricted to the operator. It includes password hashes and private accounting/receipt data. The tool applies owner-only permissions on platforms that support them; it does not encrypt backups. Copy completed backups to separate storage and retain older versions. A checksum detects accidental changes, not malicious alteration of both files.

## Restore into a separate location

Stop the backend and create a new restore parent directory:

```sh
mkdir -p /path/to/restored-ledgerdesk
python3 scripts/local_backup.py restore /path/to/private-backups/ledgerdesk-2026-10-02 /path/to/restored-ledgerdesk/ledgerdesk.mv.db --confirm-stopped
```

The tool checks the manifest, file size and checksum before creating a new database. An existing destination is never overwritten. Start the same backend version with the restored datasource URL (the URL omits `.mv.db`):

```sh
java -jar backend/target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --app.accounts.persistent=true '--spring.datasource.url=jdbc:h2:file:/path/to/restored-ledgerdesk/ledgerdesk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
```

Keep the same account mode as the original installation. In persistent mode, sign in with the stored password from backup; bootstrap environment values do not replace it. Compare the trial balance, dated reports, documents, receipts and Activity before using the restored installation. Keep the original stopped while validating. A backup predates later transactions and password changes; arrange any missing records deliberately rather than opening both copies for normal work.

Store the backend version and external configuration separately with your recovery notes. The database copy does not contain application binaries, environment secrets or deployment configuration. Restoring with another version may run migrations, so keep an untouched backup and test upgrades separately.

## Verification and limits

Six Python tests cover byte-for-byte restore, independence of the restored file, refusal to overwrite a backup/database, corruption (including same-size corruption), lock-file rejection and manifest path restrictions. Run `python3 -m unittest discover -s scripts -p 'test_*.py'`.

The extended `npm run test:persistent` workflow creates a supplier and a $125.37 owner contribution, verifies restart and offline account recovery, stops the backend, runs the actual backup/restore CLI and opens a separate restored database. It compares the full workspace state, stored owner/reviewer authentication and recovery activity, then retries the original contribution key to check that retained idempotency prevents a duplicate. Source `01f1f6685bc5a387abdaf7f592fc76949b0848d6` passed [run 37077815392](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37077815392): six backup-tool tests, 229 backend integration tests on each of H2 and PostgreSQL 17, the production frontend build and all 20 Chromium workflows. The packaged H2 process workflow completed the backup, separate-file restore, full workspace comparison, stored login/role checks and idempotent contribution retry. Receipt restoration is not exercised by this fixture; receipt bytes live in the copied database, but should be checked during an operator restore.

This is an offline local H2 backup workflow. It does not establish live backup, power-loss/crash recovery, PostgreSQL backup/restore, encrypted remote storage or scheduled backup retention. A partially copied folder after interruption must not be used; checksum verification rejects an incomplete database. PostgreSQL needs a separate database-native workflow.


The later [receipt restoration checks](receipt-restoration.md) verify PNG/JPEG attachment bytes, metadata, download headers and permissions after recovery. Earlier proof above remains the record of its original fixture.


The later [opening balance restoration checks](opening-balance-restoration.md) verify retained opening metadata and keys, a closed first statement, dated reports and cutover protections after an actual separate-database restore.
