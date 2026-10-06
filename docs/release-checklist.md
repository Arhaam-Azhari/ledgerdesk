# Before the first release

The target is one service business per installation, USD, and calendar-year earnings closing.

| Item | Status |
| --- | --- |
| Opening books and document settlement after recovery | Passed on H2 and PostgreSQL in [run 37388314053](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37388314053) |
| Login, logout, account changes and role access | Passed in [run 37396246007](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37396246007) |
| HTTPS install, restart and encrypted PostgreSQL recovery | Passed in [run 37388921759](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37388921759) |
| Starting guide | Build and browser checks passed in [run 37396791830](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37396791830) |
| Saved business name, invoice copies and recovery | Backend, browser and recovery checks passed in final combined run 37399440456 |
| Walk through a fresh installation with the written instructions | Accounting scenario passed over HTTPS in run 37399173275, including matched reconciliation, restart and encrypted recovery |
| Review current desktop/mobile screens and downloaded documents | Hosted desktop/mobile and business settings screens reviewed; normal and long customer-statement PDF samples reviewed |
| Check the final combined branch and merge the draft work | Final combined accounting and hosted runs passed; PR #49 merged into main |
| Choose the live host, domain and backup location | Pending |
| Verify the live installation before using real records | Pending |

The HTTPS and recovery runs use a disposable CI installation. They do not mean the application is already running on a public host. Backup scheduling and retention still need an operator. Multi-business support, other currencies, custom fiscal years, payroll and tax filing stay outside this release.

Combined code: [PR #49](https://github.com/Arhaam-Azhari/ledgerdesk/pull/49). Final checks: [accounting, browser and restores](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37399440456) and [hosted installation/recovery](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37399440140). The remaining checklist items concern the actual live installation.
