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

The new backend tests and browser workflows have not passed yet. Backend dependency restoration is in progress following a workspace connection interruption. No new screenshots have been published.

## Remaining before merging

1. Compile and run the backend suite; address failures.
2. Run all browser workflows and inspect the captured screens.
3. Update the README, accounting notes, architecture notes, and verification evidence.
4. Check the PostgreSQL suite on GitHub Actions and merge after all required checks pass.

The complete platform still needs bank reconciliation, period reports, roles, and deployment work.
