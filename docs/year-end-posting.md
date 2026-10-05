# Posting a calendar-year earnings close

The owner API can now post the proposal reviewed under **Reports → Year-end preview**. The browser currently supports review only; posting and history controls are separate work. A posted close clears revenue and expense balances, transfers profit or loss to retained earnings, and retains the figures and supporting review IDs used for the decision.

## Prepare and post

1. Finish due prepaid recognition and depreciation, and review the year's documents and adjustments.
2. Close the bank reconciliation ending December 31 and retain the accounting period close at that date.
3. Run the year-end preview. Resolve every blocker, including revenue/expense balances carried from earlier years.
4. Submit a year and review note using an owner account, a CSRF token and a new request key. The service rechecks the proposal while postings are locked. Reuse that same key and exact body if a connection fails; a successful retry returns the same close ID.

With the backend on port 8080 and your local owner credentials in `LEDGERDESK_USER` and `LEDGERDESK_PASSWORD`, the following posts the reviewed 2026 year. It changes the books; use fictional local records when trying the example. Reopening a posted earnings year is not supported yet.

```sh
cookie_file=$(mktemp)
csrf_file=$(mktemp)
curl --fail --silent --show-error --cookie-jar "$cookie_file" \
  'http://localhost:8080/api/csrf' > "$csrf_file"
csrf_header=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["headerName"])' "$csrf_file")
csrf_token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$csrf_file")
curl --fail --silent --show-error --cookie "$cookie_file" \
  --user "$LEDGERDESK_USER:$LEDGERDESK_PASSWORD" \
  --header "$csrf_header: $csrf_token" \
  --header 'Idempotency-Key: year-end-2026-reviewed' \
  --header 'Content-Type: application/json' \
  --data '{"year":2026,"reviewNote":"Reviewed annual earnings and closing proposal"}' \
  'http://localhost:8080/api/year-end'
rm -f "$cookie_file" "$csrf_file"
```

The note must be nonblank and at most 240 characters; the request key must be nonblank and at most 100. Years 1 through 9999 are supported. A changed body with an existing key is rejected, and a different key cannot close the same year again. Missing prerequisites, invalid details and denied writes leave no close or partial journal.

## Inspect retained history

```sh
curl --fail --silent --show-error --user "$LEDGERDESK_USER:$LEDGERDESK_PASSWORD" \
  'http://localhost:8080/api/year-end'
```

The response's `yearEndCloses` array is newest year first and contains the close ID, year/dates, entry ID, supporting accounting and bank IDs, review note, original preview snapshot, actor and timestamp. Database-style field names such as `calendar_year` and `entry_id` are used here. `snapshot` is a JSON string containing the original proposal. Reading roles can inspect history and preview; only owners can post. History and preview use `Cache-Control: no-store`.

Running preview again for a closed year returns that retained proposal with an already-closed blocker and `ready: false`. The proposal's balances describe the review before posting; current account activity and trial balance show the posted offsets. This avoids presenting the zeroed accounts as a new close proposal.

## What happens to the reports

The $100.10 revenue and $40.04 office supplies example creates one December 31 journal: debit revenue $100.10, credit supplies expense $40.04, and credit retained earnings $60.06. The journal's source ID is the retained close ID. Both temporary accounts finish at zero. Permanent asset/liability accounts, owner contributions and drawings stay separate.

Profit and loss and two-period comparisons exclude only journal entries explicitly referenced by retained earnings-close records. They still show $60.06 operating profit after closing. Trial balance, balance sheet and account activity include the real entry: accumulated unclosed earnings becomes zero and posted retained earnings increases, with total equity unchanged. Reports ending before the closing date retain their earlier balances.

A loss debits retained earnings. Equal revenue and expenses still need their two offsets even with zero net profit. An entirely inactive year retains a reviewed close with a null entry ID rather than inventing zero journal lines. The next year's temporary-account opening balances are cleared, and successive closes accumulate in retained earnings.

## Controls and current limits

The business posting lock serializes closing and competing writes. The proposal, journal, retained history, request key and audit event commit in one transaction. An audit or database failure rolls them all back. A database uniqueness constraint also prevents two closes for the same business/year.

The closing journal is a controlled exception to the already reviewed period's posting cutoff. Ordinary backdated entries remain blocked. A supporting accounting period cannot be reopened after earnings close, so its bank reconciliation remains protected too. Year-end reopening is deliberately unavailable in this milestone; a later workflow must reverse the close and preserve operating reports and history before permitting corrections.

This remains a local, single-business USD application with calendar-year earnings closing. Custom fiscal years, dividend closing, tax filing, complete opening trial-balance migration and browser posting/history controls are not implemented. Existing general database restoration checks are regression evidence; restoring a populated year-end close is a separate recovery fixture still to add.

`YearEndPostingTest` covers the worked profit, loss/zero/empty cases, report preservation, retained evidence, consecutive years, exact retries, duplicates, protected dates, rollback, concurrent requests and authenticated read/owner-write permissions on both databases. The [preview guide](year-end.md) provides the accounting basis and reviewed screen captures.

Source `db63b034eb6e40ff62e6cad739502f109926af2f` passed 306 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production build, all 27 existing Chromium workflows and native PostgreSQL restoration in [run 37241260982](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37241260982). The browser checks are regression evidence; the new posting behavior is exercised through the service and authenticated HTTP tests.
