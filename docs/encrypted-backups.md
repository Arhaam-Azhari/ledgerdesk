# Protected PostgreSQL backups (in progress)

The PostgreSQL backup tool already saves an archive and a checksum manifest. `encrypted_backup.py` wraps those two files in age encryption for storage or transfer. It checks the manifest before encryption and after decryption, refuses existing destinations, and publishes no output after a failed operation. Decryption only accepts the two expected plain files; it never extracts arbitrary archive paths.

Install the [age tools](https://github.com/FiloSottile/age). On a separate recovery machine, generate an identity and export its public recipients file:

```sh
age-keygen -o /secure/recovery-identity
age-keygen -y /secure/recovery-identity > /secure/backup-recipients
```

Keep the private identity off the application host, with another protected recovery copy. The application host only needs the public recipients file. Losing every identity copy makes encrypted backups unrecoverable.

Stop the backend and other writers, then use the existing [PostgreSQL backup procedure](postgres-backups.md). For the hosted container, set `LEDGERDESK_PG_CONTAINER` to the database container ID from `docker compose -f compose.hosted.yaml ps -q database`, and set `PGUSER=ledgerdesk`. Then encrypt the resulting folder:

```sh
python3 scripts/encrypted_backup.py encrypt /protected/backups/snapshot /protected/backups/snapshot.age --recipients /secure/backup-recipients
```

Encryption preserves the original folder. Its plaintext files should remain in protected storage only as long as needed to verify the encrypted copy. Transfer the `.age` file to a separate, access-controlled backup location. Verify the transferred file's SHA-256 matches the source, and periodically decrypt and restore a copy. A file on the same host does not protect against losing that host.

For recovery, use a protected working directory and the private identity:

```sh
python3 scripts/encrypted_backup.py decrypt /protected/backups/snapshot.age /protected/recovery/snapshot --identity /secure/recovery-identity
python3 scripts/postgres_backup.py restore /protected/recovery/snapshot recovered_books --confirm-stopped
```

Restore into a fresh database, then configure the stopped application to use it. Check retained records, account access and reports before reopening writes. The restore tool never overwrites an existing database. Remove recovery plaintext when the exercise is complete.

A starting retention policy is seven daily copies and four weekly copies, with at least one verified off-host copy. Keep the last known-good recovery point until its replacement has been checked. Scheduling and pruning are not automated yet.

Four local rejection checks pass. The real encryption/wrong-key test needs age and was skipped locally. The hosted CI workflow installs age and is prepared to encrypt a populated native PostgreSQL backup, decrypt it, restore a fresh database, and compare the application state over HTTPS. This exercise has not passed yet.
