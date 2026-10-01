# Purchases milestone progress

This branch is a development checkpoint. The default branch still contains the verified invoice milestone.

## Implemented

- Vendors and balances for posted bills.
- Operating expense categories, vendor bills, and partial or full bill payments.
- Direct expenses paid from the recorded bank account.
- Reversals for unpaid bills and mistaken direct expenses, retaining the original records.
- Authenticated receipt uploads and downloads, content validation, and database storage.
- Backend integration checks and two additional browser workflows prepared for these features.

## Verified at this checkpoint

TypeScript checking and the Vite production build passed. Git whitespace checks passed.

All 45 backend integration tests passed on H2 and all 45 passed on PostgreSQL 17 through Maven on GitHub Actions. The suite consists of the 24 existing invoice tests and 21 purchase tests, with zero failures, errors, or skipped tests in either database run.

Verified source commit: `d03462a792ebf7b92c5d062dbce1ebcb7a2fae3f`. [Backend and browser run](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36913912687). The browser job is still running at this checkpoint. New screenshots have not been published.

## Remaining before merging

1. Run all browser workflows and inspect the captured screens.
2. Update the README, accounting notes, architecture notes, and verification evidence.
3. Merge after all required checks pass.

The complete platform still needs bank reconciliation, period reports, roles, and deployment work.
