# First release finish line

The first release is a deployable accounting application for one small service business per installation. It uses USD and calendar-year earnings closing, with owner, bookkeeper and reviewer access. It must support a complete documented workflow and recovery from a backup. A public repository and passing local tests alone do not complete that release.

The existing sales, purchases, ledger, reports, bank reconciliation, adjustments, receipts, accounts, closing and recovery features provide the accounting foundation. Work from here should close the gaps below rather than add unrelated features.

## Remaining work in order

| Stage | What must be delivered | Evidence needed before calling it done |
| --- | --- | --- |
| Opening books | Reviewed opening trial balance with supported source records, cutover and prevention of duplicate setup | Balanced and rejected examples, document settlement continuity, dated reports, browser instructions and real restoration |
| Hosted access | Replace local Basic-auth usage in hosted mode with a reviewed session login and logout | Role enforcement, CSRF, session expiry/logout, failed login behavior and private-response checks |
| Deployment and recovery | Reproducible HTTPS deployment, external configuration, protected backup storage and retention instructions | Fresh install, restart, backup/restore exercise, secret-handling review and a tested recovery procedure |
| First-use workflow | Business setup, clear validation and useful empty states for supported workflows | A new operator can set up fictional books and complete the documented workflow without undocumented preparation |
| Release acceptance | Review accounting, authorization, mobile access, exports, documentation and remaining limitations together | Full checks, retained proof, current screenshots, demo instructions and a release checklist with no unresolved blocking items |

Opening books comes first because the current setup records only a cleared bank balance. An existing business also needs supported carried balances and documents it can subsequently settle. A generic journal to control accounts without corresponding documents would leave aging and payments inconsistent.

## Opening-books design to resolve next

The [opening-books preview](opening-books.md) now validates and posts supported balances and fully unpaid documents, retaining the reviewed sources and cutoff. Settlements and accounting controls have service/API tests; browser import, populated recovery and final operator review remain. The design and acceptance checklist is:

- Pick an explicit cutover date. Operating activity begins afterward; historical income must not appear as new operating revenue simply because books were imported.
- Represent outstanding customer and supplier amounts with retained source records that remain payable/collectible. Reconcile their totals with the corresponding control-account balances.
- Carry supported permanent-account balances with a balanced, reviewed opening entry. Specify how opening retained earnings and owner equity are distinguished.
- Define how existing opening-bank setup interacts with the broader import so bank funds cannot be recorded twice.
- Decide which prepaid and fixed-asset histories can be carried correctly, including their remaining schedules. Reject unsupported cases explicitly rather than accepting incomplete accounting records.
- Preview and validate before posting. Retain the reviewed request, actor, cutover, posting references and retry key; serialize setup with other writes and roll back failures.
- Verify opening reports, bank carry-forward, subsequent settlements, date protections and recovery on both databases before adding browser controls.

This is a design checklist, not a claim that these imports already exist. The implementation may require several small checkpoints: supported model and preview, posting and controls, browser workflow, and populated recovery evidence.

## Features outside this release

Multi-company SaaS, additional currencies, custom fiscal years, payroll, tax filing, dividend accounting, automatic bank feeds and new adjustment families are later product work. The first release must state its supported opening-data limits. It must also make its single-business installation boundary clear and review every supported API for that boundary.

One business per installation still needs hosted security and operational testing. It does not imply that the current local Basic-auth setup is ready for public use. Hosting credentials, provider configuration and costs may require separate deployment decisions once a concrete deployment is prepared.

## Planning estimate

Allow roughly 10–15 further focused build cycles for this first-release scope. This is a planning estimate, not a measured completion percentage or a delivery promise. Opening records and hosted security are the largest uncertainties; findings can change the estimate. Work cycles can stay short, while CI and real process checks may take additional time.

After each stage, update [project status](project-status.md) with delivered behavior and link the tested PR/run in [verification notes](verification.md). At the end, evaluate this checklist instead of treating the number of PRs or tests as the completion percentage.
