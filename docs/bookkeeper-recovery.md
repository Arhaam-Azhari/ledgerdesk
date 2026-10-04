# Bookkeeper accounts after upgrade and restore

A bookkeeper's access must survive database recovery without being promoted to an owner or silently changed by bootstrap credentials. The extended H2 and PostgreSQL process fixtures create a stored bookkeeper before backup and use that account to create the original supplier. Existing opening balances, dated reports, receipts, closed statements and accounting close/reopen history remain part of the recovery scenario.

After restoration, checks compare account IDs, usernames, roles and enabled states through the owner-only account list. The stored bookkeeper password works and a wrong password fails. The bookkeeper can read the full restored workspace and download the same PNG/JPEG/PDF receipt bytes and headers. Retrying its original supplier request returns the same ID without adding activity.

Valid-CSRF requests against accounts, opening balances, owner transfers, accounting close and bank statement reopening must return 403. A routine expense dated in the closed accounting period must return 400. The full workspace must still match its baseline after these reads, retries and rejected writes.

Only after the original recovery comparisons finish does the fixture allow deliberate new work: a bookkeeper creates another supplier, retries it and checks the retained activity actor. Journal lines stay unchanged. The restored owner then demotes the bookkeeper to reviewer and confirms write denial, disables it and confirms authentication denial. These deliberate changes are checked separately from baseline preservation.

## Populated database upgrade

`BookkeeperMigrationTest` runs Flyway through version 21 in an isolated schema, seeds owner/reviewer users (including a disabled account and a second-business membership), balanced journal lines, a retained command and activity. Version 21 must reject BOOKKEEPER. Applying version 22 must retain all seeded rows exactly, including password hashes and account/membership IDs. Existing logins and enabled states remain; changed bootstrap values do not replace them. A bookkeeper role then becomes valid, while invalid roles and missing user/business foreign keys stay rejected. A second migration run must apply nothing.

The test runs against H2 and PostgreSQL using the same database selection as the integration suite. It does not modify the main test schema. It covers this populated membership upgrade; it is not a general rollback or online deployment migration strategy.

## Verification checkpoint

Python compilation, all 14 backup-tool tests, the frontend production build and diff checks pass locally. Full integration, real H2/PostgreSQL restoration and browser regression runs are pending. No application UI source changes are included; the existing reviewed bookkeeper screenshots continue to illustrate the screen, while process tests provide the recovery evidence.
