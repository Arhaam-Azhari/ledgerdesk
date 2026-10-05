# Restoring earnings closing history

A restored ledger needs its closing records as well as its journals. Those records identify the entries that operating profit reports exclude, retain the reviewed figures, and track which earnings year is still closed. Missing history could leave a balanced ledger with incorrect profit reporting or permit a duplicate closing.

## Recovery checks

Use the [H2](local-backups.md) or [PostgreSQL](postgres-backups.md) backup instructions to stop the application and restore into a separate file or database. Start the same backend version against that destination, retaining the configured account mode. Keep the original stopped during comparison.

Before resuming work:

1. Open **Reports → Year-end preview → Load closing history**. Compare the closed/reopened records, review notes, actors, versions and evidence references with your records at the backup cutoff.
2. Expand each retained review. Check its figures and original closing proposal. A reopened record must retain that original review and its closing entry reference, with a separate reversal reference and reopening reason.
3. Compare dated operating profit, trial balance, balance sheet and cash activity. Closing entries and their reversals remain real journal entries; operating profit excludes them through the retained history references. Total equity should agree with the saved books.
4. Confirm the active year remains closed and its supporting period and bank reviews remain closed. Restoring does not intentionally reopen any review.
5. Inspect account **3300 · Retained earnings** and its posting evidence. Follow the existing [reopening order](year-end-posting.md#reopen-correct-and-close-again) only if a correction is actually required.

Do not post another closing simply because you restored a database. A retained active close blocks a second closing with a new key. Retrying an original request with its original details and key returns its original result, even if that historical record has since been reopened and replaced. A historical retry does not change the current active record. New intentional actions use new request keys and the current record version.

Backups contain the history at their cutoff. They omit later closings, reversals, corrections and password changes. Reconcile those differences with source documents before using restored books for normal work.

## Executable fixture

`scripts/verify_year_end_restore.py` runs the same scenario against the packaged backend on H2 and PostgreSQL 17. It starts a fresh source database, stops it before backup, invokes the real backup/restore CLI, and starts a separate destination. It does not simulate a restore by reopening the original database.

The fictional 2026 books contain a $1,000.25 cleared opening bank balance, an unpaid $100.10 invoice and an unpaid $40.04 supplier bill. Annual operating profit is $60.06. Bank and accounting reviews are closed at December 31. The fixture closes earnings, reopens that record and closes again, leaving one reopened record and one active record before backup.

It verifies:

- Exact history, full workspace, dated reports, earlier reports, cash activity, account activity and preview after restoration. This includes original snapshot strings, actor/time metadata, versions, closing/reversal references and supporting review IDs.
- Stored reviewer/bookkeeper access to populated history and denial of their closing/reopening writes.
- Original close/reopen request keys returning their original IDs without changing the restored workspace or replacement close.
- Rejection of duplicate closes, changed request details, stale versions, protected period reopening and backdated contributions, with no changes to saved evidence.
- An intentional owner reopening creating exactly three reversed lines with swapped debits/credits and the correct source reference. Operating reports return to their pre-closing figures, retained earnings returns to zero, and supporting reviews stay closed.
- Retried reopening and closing requests adding no journal or audit records; a deliberate replacement close retains both earlier records unchanged and transfers $60.06 once.

The H2 scenario runs in the browser CI job before the existing Chromium workflows. The PostgreSQL scenario runs after the existing native receipt/password recovery fixture in the restoration job. Neither scenario adds a new browser workflow or backend integration-test count. A failing scenario preserves its backend log as a CI artifact.

With Java 17, Python 3 and the packaged backend available, the isolated H2 check can be run from the repository root:

```sh
python3 scripts/verify_year_end_restore.py --database h2
```

Port 8093 must be free. The PostgreSQL variant uses the same port and reserves the disposable names `earnings_backup_source` and `earnings_backup_restored` on localhost:5432. It requires the existing backup-tool connection configuration and create-database permission. Run it only against an isolated test server; it refuses existing names and never drops a database.

```sh
python3 scripts/verify_year_end_restore.py --database postgres
```

These checks cover a populated profit close/reopen/replacement cycle. Loss, empty-year and multi-year restoration are not included in this fixture; those accounting cases have separate service tests. This does not establish crash recovery, live copying, point-in-time recovery, encrypted storage or backup retention.

## Verified run

PR #38 source `2b39091da64c20ff56d31e9b8218628af67ada6a` passed [run 37282486948](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37282486948): 315 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, all 14 backup-tool tests, the production frontend build and all 28 Chromium workflows. The packaged-backend H2 scenario and native PostgreSQL earnings scenario both completed real separate-database restoration and every assertion above. The existing receipt/password restoration checks also passed.

No screen changed in this milestone. The existing [reviewed desktop/mobile captures](year-end-posting.md#browser-proof) show the closing/history interface; the executable recovery scenarios and CI logs provide the restoration evidence.
