# Project status

Ledgerdesk currently works as a local, single-business USD accounting application. The development history records each feature and recovery milestone. Receipt-restoration checks extend that recovery evidence. This is a working portfolio application with substantial accounting behavior; finishing a hosted product still requires the work below.

| Area | Current state |
| --- | --- |
| Sales and purchases | Invoices, bills, direct expenses, payments, retained corrections and receipt attachments implemented |
| Ledger and reports | Balanced entries, trial balance, dated financial reports, customer/vendor aging, cash activity and CSV exports implemented |
| Profit comparison | Two-period accounting API, browser editor, category/total changes and dated CSV export implemented |
| Bank evidence | CSV import, reviewed matching, statement reconciliation and closed-date protections implemented |
| Accounting adjustments | Owner transfers, expense reclassification, accruals and bill handoff, prepaid schedules and straight-line asset depreciation implemented |
| Local access | Stored owner/bookkeeper/reviewer accounts, account administration, self-service password changes, last-owner protection and offline owner recovery implemented |
| Database recovery | Separate-file H2 restore and native PostgreSQL 17 restore verified through packaged-backend process tests |
| Attachment recovery | PNG/JPEG restore verified in PR #15; PR #16 extends the same checks to a static PDF |
| Hosted release | Secure hosted sessions, HTTPS deployment and operational configuration remain |
| Broader access | Routine bookkeeper permissions implemented; business selection and complete business isolation remain |
| Opening bank balance | Cleared nonnegative opening, retained setup, first-statement carry-forward and H2/PostgreSQL restoration verified |
| Period review | Month-end prerequisite checks, retained reports, owner close/reopen history, date protections and H2/PostgreSQL restoration verified |
| Accounting completeness | Complete opening trial-balance migration, fiscal-year closing and additional adjustment types remain |
| Operations | Encrypted backup storage, scheduled retention and broader recovery fixtures remain |

The core workflows can already demonstrate a document progressing through payment, ledger posting, bank reconciliation, reporting and recovery. The remaining work is meaningful: more roles and businesses affect authorization throughout the application, and hosted sessions/deployment change how credentials and private records are handled.

There is no measured completion percentage because the final hosted scope has not been frozen. Counting merged PRs or tests as a percentage would obscure those larger remaining tasks. The current [README](../README.md), [development history](development-history.md) and [verification notes](verification.md) show what is implemented and what evidence supports it.
