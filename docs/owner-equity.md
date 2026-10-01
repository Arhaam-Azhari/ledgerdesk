# Owner funding and withdrawals

This checkpoint adds the posting API and bank matching for a single owner of the demo business. The browser form records contributions and withdrawals and lists their retained history. A correction workflow remains the next step. It does not model share issuance, multiple owners, loans or tax treatment.

`POST /api/equity` requires the workspace credentials, CSRF token and `Idempotency-Key`. Supply `kind` (`CONTRIBUTION` or `DRAWING`), `postedOn`, `memo` and a positive decimal `amount` with at most two decimal places. The state endpoint returns the retained records in `equityTransactions`.

A contribution debits Business bank (1000) and credits Owner contributions (3000). A drawing debits Owner drawings (3100) and credits Business bank. Drawings have a debit equity balance, reducing total owner equity. Neither changes revenue, expenses or customer/vendor aging. A $1,000 contribution followed by a $200 drawing leaves bank and posted equity at $800, with no profit. There is no bank connection: record a transfer that actually happened, rather than treating this as a money-transfer command. As elsewhere in the demo, negative recorded bank balances are possible.

The business lock, exact decimal journal helper, request-key check and activity record are shared with existing postings. The retained transfer, two journal lines and activity event commit together. Closed reconciliation dates reject new transfers. Retrying a successful command returns its existing ID, including after the period closes; changed details cannot reuse that key.

Owner cash entries appear as bank matching candidates with their direction and memo. The same one-to-one amount checks apply. Pending funding is an outstanding deposit and pending drawings are outstanding payments during reconciliation. Importing or matching a statement does not post the owner transfer again.

Migration V7 adds the two accounts and transfer table without changing earlier migration files. General adjustments, reversals and reviewed browser proof are still pending; posted owner records currently have no edit/delete endpoint. This checkpoint should remain a draft until corrections and browser verification are complete.

Eight integration tests cover exact contributions/drawings and equity reports, historical cutoff, request retries/conflicts, input validation, transaction rollback, matching/reconciliation with closed-date protection, outstanding funding, and endpoint authentication/CSRF/request keys. All 109 backend integration tests passed on H2 and all 109 passed on PostgreSQL 17, with zero failures, errors or skipped tests in [run 36941728558](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36941728558), source `38b9202ce48d2ab38f730bfdcc0b0c78a14bacd8`. The production frontend build and all ten existing Chromium workflows also passed. Those browser checks cover existing screens; an owner form and its browser proof remain pending.

## Browser workflow

Open **Owner transfers**. Choose Contribution to business or Withdrawal for owner, enter the actual transfer date, a memo and a positive USD amount, then choose **Record transfer**. Totals and history include all recorded dates, including future dates; use Reports for a historical cutoff. Net owner funding excludes accumulated earnings.

After an uncertain refresh, the form retains its details and request key so retrying the same transfer does not record it twice. Editing the details starts a different command; reload and inspect history first if the result is uncertain.

Build the backend JAR as described in the reporting notes, install frontend dependencies and Chromium, then run `npm run test:equity` in `frontend`. The isolated check uses ports 8083 and 5176. It checks both directions, historical equity without profit, invalid decimal precision, an interrupted refresh and exact-once retry, retained history, and a 390-pixel layout. CI verification and captured examples for this new screen are pending.
