# Profit comparison

Profit comparison shows revenue, expense categories and net profit across two periods, so an owner can see where a change in profit came from. Open **Reports**, choose **Compare profit**, enter both date ranges and choose **Run comparison**. **Single period reports** returns to the existing financial reports and aging views.

The defaults compare the current month to date with the previous full month. These lengths differ, so review the ranges before drawing conclusions. The results retain both periods and show previous, current and current-minus-previous USD amounts. The table can be scrolled horizontally on a narrow screen. Owners, bookkeepers and reviewers have the same read-only comparison controls.

**Export comparison CSV** downloads the displayed results with both ranges in the file and filename. It uses UTF-8 with a byte-order mark, quoted fields, CRLF rows and plain decimal amounts. User text is protected against spreadsheet formulas; signed monetary amounts remain numbers. Editing any date, reloading the workspace, changing report modes or a failed comparison clears obsolete results before another export. If a read fails, retry **Run comparison**; it does not post accounting entries.

## Dates and response

Call `GET /api/reports/profit-comparison` with `startsOn`, `endsOn`, `previousStartsOn` and `previousEndsOn`. For example:

```text
/api/reports/profit-comparison?startsOn=2026-10-01&endsOn=2026-10-31&previousStartsOn=2026-09-01&previousEndsOn=2026-09-30
```

The workspace login is required. Owners, bookkeepers and reviewers can read this report. The response uses `Cache-Control: no-store`; all monetary amounts are decimal strings.

`current` and `previous` each contain the exact selected dates and a `profitLoss` result with accounts, revenue, expenses and net profit. `change` has those same amounts and account categories, calculated as current minus previous. A positive expense change means expense increased; it is not automatically a favorable result. Signed reversals remain visible, and zero categories are included.

Both date ranges are inclusive. Each must have a start on or before its end within years 1–9999. The previous period must end before the current one starts. Gaps and unequal lengths are allowed because owners may compare a partial period with another period; the caller should display both ranges clearly. Gap transactions belong to neither period. No annualization, percentage growth or favorable/unfavorable assessment is calculated.

## Worked example

| Amount | Previous | Current | Change |
| --- | ---: | ---: | ---: |
| Revenue | $300.30 | $1,101.10 | $800.80 |
| Office supplies | $100.15 | $0.00 | −$100.15 |
| Software subscriptions | $0.00 | $50.10 | $50.10 |
| Total expenses | $100.15 | $50.10 | −$50.05 |
| Net profit | $200.15 | $1,051.00 | $850.85 |

A current-period payment against a previous-period invoice changes cash and receivables, without increasing current revenue. Drafts and future postings are excluded. If a previous invoice or bill is reversed in the current period, the negative income or expense belongs to the current period; the earlier report keeps its original posting.

## Implementation and checks

The original report and the comparison share one profit calculation. The comparison reads both periods in one repeatable-read transaction, so a concurrent posting cannot appear in only one side of that comparison. Category changes match by account code. Java `BigDecimal` subtraction preserves cents, including negative changes. Reading does not add journal lines, activity or request keys.

Six new integration tests cover known exact totals/category changes, inclusive boundaries, payments/drafts/future entries, unchanged books/activity/commands, dated reversals, unequal periods and gaps, leap-day/single-day ranges, empty categories, date limits, rejected invalid/overlapping periods, authentication, reading roles and cache headers. Run them with `mvn -f backend/pom.xml -Dtest=ReportTest test` against a disposable test database. The regular CI suite runs these with all existing tests on H2 and PostgreSQL 17.

The API source `7638378c4904ee6d31e7e35e7e2d840284001abf` passed all three jobs in [run 37227961068](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37227961068): 275 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production frontend build, all 24 existing Chromium workflows and native PostgreSQL restoration. That checkpoint covers the API and existing screen regressions.

The comparison screen builds locally. Its extended reports workflow checks known prior/current values, later settlement payments, negative changes, downloaded CSV content and dates, unequal lengths, rejected overlap, a failed read/retry, cleared results, empty periods, unchanged books/activity and a 390-pixel layout. Reviewer and bookkeeper workflows also run the comparison. Run `npm run test:reports`, `npm run test:reviewer` and `npm run test:bookkeeper` in `frontend` after packaging the backend and installing Chromium. CI results and reviewed captures for this screen are pending at this checkpoint.
