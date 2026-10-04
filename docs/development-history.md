# Development history

Ledgerdesk grew from an invoicing workspace into a local accounting application. The pull requests below keep each milestone's file changes, commits and verification notes together. Open a PR's **Files changed** tab to inspect the implementation, or its **Commits** tab to follow the individual checkpoints. Merged PRs remain available after their branches are removed.

| Pull request | Milestone |
| --- | --- |
| [#1](https://github.com/Arhaam-Azhari/ledgerdesk/pull/1) | Add vendor bills, expenses, and receipt attachments |
| [#2](https://github.com/Arhaam-Azhari/ledgerdesk/pull/2) | Add bank imports, reviewed matching and statement reconciliation |
| [#3](https://github.com/Arhaam-Azhari/ledgerdesk/pull/3) | Add dated financial reports and CSV exports |
| [#4](https://github.com/Arhaam-Azhari/ledgerdesk/pull/4) | Add owner funding, withdrawals and retained corrections |
| [#5](https://github.com/Arhaam-Azhari/ledgerdesk/pull/5) | Add expense category adjustments and retained reversals |
| [#6](https://github.com/Arhaam-Azhari/ledgerdesk/pull/6) | Add accrued expense editor, history and dated reversals |
| [#7](https://github.com/Arhaam-Azhari/ledgerdesk/pull/7) | Add supplier bill handoff and linked accrual history |
| [#8](https://github.com/Arhaam-Azhari/ledgerdesk/pull/8) | Add prepaid expense schedules and monthly recognition |
| [#9](https://github.com/Arhaam-Azhari/ledgerdesk/pull/9) | Add equipment register and straight-line depreciation |
| [#10](https://github.com/Arhaam-Azhari/ledgerdesk/pull/10) | Add cash activity report and traceable CSV export |
| [#11](https://github.com/Arhaam-Azhari/ledgerdesk/pull/11) | Add read-only reviewer workspace and authorization |
| [#12](https://github.com/Arhaam-Azhari/ledgerdesk/pull/12) | Add stored accounts, owner administration and offline recovery |
| [#13](https://github.com/Arhaam-Azhari/ledgerdesk/pull/13) | Add offline local database backup and restore |
| [#14](https://github.com/Arhaam-Azhari/ledgerdesk/pull/14) | Add PostgreSQL backup and restore |
| [#15](https://github.com/Arhaam-Azhari/ledgerdesk/pull/15) | Verify receipt attachments after database restoration |
| [#16](https://github.com/Arhaam-Azhari/ledgerdesk/pull/16) | Verify PDF receipt restoration |
| [#17](https://github.com/Arhaam-Azhari/ledgerdesk/pull/17) | Add cleared opening bank balance setup and statement carry-forward |
| [#18](https://github.com/Arhaam-Azhari/ledgerdesk/pull/18) | Verify opening balances and closed statements after restoration |
| [#19](https://github.com/Arhaam-Azhari/ledgerdesk/pull/19) | Use valid cash report dates after an opening balance |
| [#20](https://github.com/Arhaam-Azhari/ledgerdesk/pull/20) | Add accounting period review, close/reopen history and restoration proof |
| [#21](https://github.com/Arhaam-Azhari/ledgerdesk/pull/21) | Delegate routine accounting to stored bookkeeper accounts |
| [#22](https://github.com/Arhaam-Azhari/ledgerdesk/pull/22) | Verify bookkeeper accounts after populated upgrade and database restoration |
| [#23](https://github.com/Arhaam-Azhari/ledgerdesk/pull/23) | Let stored accounts change their own password |
| [#24](https://github.com/Arhaam-Azhari/ledgerdesk/pull/24) | Verify self-changed passwords through restart and database restoration |
| [#25](https://github.com/Arhaam-Azhari/ledgerdesk/pull/25) | Compare accrual profit and category changes across two periods through the reporting API |
| [#26](https://github.com/Arhaam-Azhari/ledgerdesk/pull/26) | Add the profit comparison screen and dated CSV export |
| [#27](https://github.com/Arhaam-Azhari/ledgerdesk/pull/27) | Add dated customer statement accounting and traceable ledger movements |
| [#28](https://github.com/Arhaam-Azhari/ledgerdesk/pull/28) | Add customer statement screen, ledger references and dated CSV export |
| [#29](https://github.com/Arhaam-Azhari/ledgerdesk/pull/29) | Add authenticated dated customer statement PDF downloads and rendered proof |
| [#30](https://github.com/Arhaam-Azhari/ledgerdesk/pull/30) | Download customer statement PDFs from Reports with retry and role checks |
| [#31](https://github.com/Arhaam-Azhari/ledgerdesk/pull/31) | Explain dated account balances through individual ledger lines |
| [#32](https://github.com/Arhaam-Azhari/ledgerdesk/pull/32) | Add dated account activity screen, debit/credit presentation and signed CSV evidence |
| [#33](https://github.com/Arhaam-Azhari/ledgerdesk/pull/33) | Preview calendar-year earnings offsets and prerequisites without posting |
| [#34](https://github.com/Arhaam-Azhari/ledgerdesk/pull/34) | Review year-end earnings, balances, proposed closing lines and prerequisites in Reports |

The descriptions record the supported scope and remaining work at that milestone. For example, the reviewer PR used configured logins; the later account PR added database-backed users. Read the current README and feature guides for today's behavior.

Feature guides under `docs/` explain the accounting entries, worked figures, supported limits and reproduction steps. `verification.md` links the test runs and reviewed screenshots. Screenshots show particular tested workflows; the tests provide broader evidence about rollback, authorization and retries.

This is still a local, single-business USD application. A public repository and passing checks do not mean it is ready to host real business records. Hosted sessions, broader roles and business isolation remain separate milestones.
