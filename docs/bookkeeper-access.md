# Bookkeeper access

A service business may delegate daily accounting while keeping access administration and closed-period decisions with its owner. Stored accounts now support a BOOKKEEPER role alongside OWNER and REVIEWER. Enable persistent account mode, sign in as an owner, open **Accounts** and choose **Bookkeeper · routine accounting** when creating an account. An owner can also change an existing account's role.

| Operation | Owner | Bookkeeper | Reviewer |
| --- | --- | --- | --- |
| Read reports, ledger, receipt downloads and period history | Yes | Yes | Yes |
| Documents, payments, receipts and retained corrections | Yes | Yes | No |
| Expense adjustments, accruals, prepaid schedules and assets | Yes | Yes | No |
| Bank import, matching and statement reconciliation | Yes | Yes | No |
| Opening balance and owner transfers | Yes | No | No |
| Accounting close/reopen and statement reopening | Yes | No | No |
| Account list, creation, role changes and password reset | Yes | No | No |

Bookkeepers see an explanation of their limits. Owner-only navigation is absent, accounting period history remains readable and statement history has no reopening form. Owners must reopen a protected statement or accounting period before a bookkeeper can correct its books. An owner's final accounting close is a separate review from routine statement reconciliation.

The server delegates only an explicit list of POST routes. Unknown routes and other write methods stay owner-only. All writes still require CSRF, and existing validation, closed-date guards, balanced postings, activity and request retries apply. A changed role or disabled account affects the next authenticated request. A bookkeeper cannot use account administration to promote themselves, and last-owner protection remains in force.

Migration V22 preserves user IDs, password hashes and existing memberships while expanding the membership role constraint. Bookkeepers are created through persistent account administration; configured-login mode retains its existing owner/reviewer setup. This remains a single-business local application, with no approval queue or hosted sessions. Bookkeepers can inspect all accounting information for the business; this role is unsuitable when individual documents must be confidential from staff.

## Verification checkpoint

The production frontend build and diff checks pass locally. Seven new integration tests cover stored-role identity, routine posting with an exact retry and activity actor, CSRF, owner-only actions, unknown routes/methods, module validation, disabling/demotion and last-owner/role constraints. The new Chromium workflow signs in with a real stored bookkeeper, posts a vendor, checks absent owner controls, exercises direct forbidden API calls and captures desktop/mobile views. Run `npm run test:bookkeeper` from `frontend` after packaging the backend and installing Chromium; ports 8096 and 5188 must be free.

The full H2/PostgreSQL integration, Chromium and PostgreSQL restore checks are pending for this checkpoint. Captures must be downloaded and visually reviewed before being presented as reviewed proof. Bookkeeper-specific backup restoration and existing-database migration fixtures remain further verification work.
