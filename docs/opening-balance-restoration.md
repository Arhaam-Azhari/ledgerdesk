# Opening bank balance after restoration

This checkpoint extends the existing real database recovery fixtures to include a cleared opening bank balance and a closed first statement. It changes the verification scenarios, not the backup formats or application posting rules.

## Scenario and expected figures

The isolated books start with $1,000.25 cleared at September 30. October 1 adds $125.37 owner funding, a $40 unpaid supplies bill and a $25 paid software expense. The existing PNG, JPEG and static PDF receipt fixtures remain attached.

The October cash bridge has $1,000.25 opening cash, $125.37 receipts, $25 payments and $1,100.62 closing cash. Profit is a $65 loss; assets are $1,100.62, liabilities $40 and equity $1,060.62. The opening is equity, not income or a current receipt.

The October statement has $1,000.25 opening and closing bank balance. The later funding and paid expense remain outstanding book items: $125.37 deposits and $25 payments. These adjust the bank balance to $1,100.62, matching the books. The opening itself is not an outstanding deposit. The first statement is closed before backup, preserving its snapshot and date protections.

## Restore checks

The H2 workflow restarts the packaged backend, performs offline owner recovery, shuts it down, copies the database through the backup CLI and restores to a separate file. The PostgreSQL workflow stops the packaged backend, makes a native custom archive and restores it into a new PostgreSQL 17 database. Both workflows reopen the restored database through a new backend process.

They compare the complete workspace, including opening metadata, journal lines, receipts, activity and the closed statement. Dated cash and financial reports must equal the pre-backup results. Retrying the original opening key must return its existing ID, even though the statement is now closed; a new setup key is rejected. Reviewer setup attempts remain forbidden. An owner posting on the opening date and a cash report starting on that date are rejected without changing the restored workspace. A November preview carries the bank balance and retains the outstanding items without posting or altering the saved close.

These checks extend the existing stored-account, receipt-byte/header, contribution-retry and restore-target protections. They do not establish crash recovery, a live backup, opening trial-balance migration or recovery across database engine versions.

## Reproduce

Package the backend first (`cd backend` then `mvn -B package -DskipTests`). From `frontend`, run `npm run test:persistent` for the real H2 process workflow. For PostgreSQL, the `postgres-restore` GitHub Actions job supplies the disposable PostgreSQL 17 service and runs `python3 scripts/verify_postgres_restore.py`; see [PostgreSQL restore setup](postgres-backups.md) for the environment and operator requirements.

Execution of this extended fixture is pending for this checkpoint. Earlier successful recovery runs do not yet prove restoration of opening balances; results will be recorded after the new run completes.
