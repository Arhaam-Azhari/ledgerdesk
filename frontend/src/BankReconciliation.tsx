import type { OpeningState } from "./OpeningBankBalance";
import { useEffect, useState } from "react";
import { cents, dollars } from "./money";

export type Statement = {
  startsOn: string;
  endsOn: string;
  openingBalance: string;
  closingBalance: string;
};
export type ReconciliationPreview = {
  openingBalance: string;
  closingBalance: string;
  importedMovement: string;
  statementDifference: string;
  bookBalance: string;
  outstandingDeposits: string;
  outstandingPayments: string;
  adjustedBankBalance: string;
  bookDifference: string;
  unmatchedTransactions: {
    id: string;
    posted_on: string;
    description: string;
    amount: string;
  }[];
  outstandingEntries: {
    line_id: string;
    entry_date: string;
    memo: string;
    amount: string;
  }[];
  futureDatedMatches: {
    transaction_id: string;
    posted_on: string;
    entry_date: string;
    amount: string;
  }[];
};
export type ReconciliationState = {
  bankReconciliations: {
    id: string;
    starts_on: string;
    ends_on: string;
    opening_balance: string;
    closing_balance: string;
    snapshot: string;
    status: string;
    version: number;
    closed_by: string;
    closed_at: string;
    reopen_reason: string | null;
  }[];
};
const nextDay = (date: string) =>
  new Date(Date.parse(date + "T00:00:00Z") + 86400000)
    .toISOString()
    .slice(0, 10);
function Summary({ result }: { result: ReconciliationPreview }) {
  const rows: [string, string][] = [
    ["Statement opening balance", result.openingBalance],
    ["Imported movement", result.importedMovement],
    ["Statement closing balance", result.closingBalance],
    ["Statement difference", result.statementDifference],
    ["Book balance", result.bookBalance],
    ["Outstanding deposits", result.outstandingDeposits],
    ["Outstanding payments", result.outstandingPayments],
    ["Adjusted bank balance", result.adjustedBankBalance],
    ["Book difference", result.bookDifference],
  ];
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Calculation</th>
            <th>USD</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label}>
              <td>{label}</td>
              <td>{dollars(cents(value))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function BankReconciliation({
  data,
  busy,
  canReopen,
  preview,
  act,
}: {
  data: ReconciliationState &
    OpeningState & {
      bankTransactions: { posted_on: string }[];
      ledger: { entry_date: string }[];
    };
  busy: boolean;
  canReopen: boolean;
  preview: (statement: Statement) => Promise<ReconciliationPreview | null>;
  act: (path: string, body: object, success: string) => Promise<boolean>;
}) {
  const latest = data.bankReconciliations
    .filter((r) => r.status === "CLOSED")
    .sort((a, b) => b.ends_on.localeCompare(a.ends_on))[0];
  const today = new Date().toISOString().slice(0, 10);
  const opening = data.openingBankBalances[0];
  const initialStart = latest
    ? nextDay(latest.ends_on)
    : opening
      ? nextDay(opening.as_of)
      : [
          ...data.bankTransactions.map((r) => r.posted_on),
          ...data.ledger.map((r) => r.entry_date),
          today,
        ].sort()[0];
  const [statement, setStatement] = useState<Statement>({
    startsOn: initialStart,
    endsOn: initialStart > today ? initialStart : today,
    openingBalance: latest?.closing_balance ?? opening?.balance ?? "0.00",
    closingBalance: "0.00",
  });
  const [result, setResult] = useState<ReconciliationPreview | null>(null);
  const [reason, setReason] = useState("");
  useEffect(() => {
    setResult(null);
    setStatement({
      startsOn: initialStart,
      endsOn: initialStart > today ? initialStart : today,
      openingBalance: latest?.closing_balance ?? opening?.balance ?? "0.00",
      closingBalance: "0.00",
    });
  }, [data]);
  const ready =
    result &&
    cents(result.statementDifference) === 0n &&
    cents(result.bookDifference) === 0n &&
    !result.unmatchedTransactions.length &&
    !result.futureDatedMatches.length;
  function change(field: keyof Statement, value: string) {
    setStatement((current) => ({ ...current, [field]: value }));
    setResult(null);
  }
  return (
    <>
      <p className="intro">
        Compare the bank statement with the books, review outstanding items,
        then close the checked period.
      </p>
      <section className="card">
        <h2>Statement details</h2>
        <p>
          {latest
            ? `Books are protected through ${latest.ends_on}. The next statement starts ${nextDay(latest.ends_on)} with its closing balance carried forward.`
            : opening
              ? `The first statement starts ${nextDay(opening.as_of)} with the cleared opening balance of ${dollars(cents(opening.balance))}.`
              : "The first close starts from zero and includes the beginning of the recorded books and bank history."}
        </p>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            setResult(await preview(statement));
          }}
        >
          <label>
            Statement start
            <input
              type="date"
              required
              disabled={busy}
              value={statement.startsOn}
              onChange={(e) => change("startsOn", e.target.value)}
            />
          </label>
          <label>
            Statement end
            <input
              type="date"
              required
              disabled={busy}
              value={statement.endsOn}
              onChange={(e) => change("endsOn", e.target.value)}
            />
          </label>
          <label>
            Opening balance
            <input
              required
              inputMode="decimal"
              disabled={busy}
              value={statement.openingBalance}
              onChange={(e) => change("openingBalance", e.target.value)}
            />
          </label>
          <label>
            Closing balance
            <input
              required
              inputMode="decimal"
              disabled={busy}
              value={statement.closingBalance}
              onChange={(e) => change("closingBalance", e.target.value)}
            />
          </label>
          <button disabled={busy}>Preview reconciliation</button>
        </form>
      </section>
      {result && (
        <section className="card">
          <h2>Reconciliation review</h2>
          <Summary result={result} />
          <p>
            Closing balance + outstanding deposits − outstanding payments =
            adjusted bank balance. Check the actual statement and supporting
            records; equal totals alone do not prove a correct match.
          </p>
          <h3>Outstanding book entries</h3>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Description</th>
                  <th>Amount</th>
                </tr>
              </thead>
              <tbody>
                {result.outstandingEntries.map((r) => (
                  <tr key={r.line_id}>
                    <td>{r.entry_date}</td>
                    <td>{r.memo}</td>
                    <td>{dollars(cents(r.amount))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!result.outstandingEntries.length && (
            <p>No outstanding book entries.</p>
          )}
          <h3>Unmatched bank rows</h3>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Description</th>
                  <th>Amount</th>
                </tr>
              </thead>
              <tbody>
                {result.unmatchedTransactions.map((r) => (
                  <tr key={r.id}>
                    <td>{r.posted_on}</td>
                    <td>{r.description}</td>
                    <td>{dollars(cents(r.amount))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!result.unmatchedTransactions.length && (
            <p>No unmatched bank rows through this statement end.</p>
          )}
          {result.futureDatedMatches.length > 0 && (
            <p>
              {result.futureDatedMatches.length} bank match(es) point to book
              entries dated after the statement end. Correct these before
              closing.
            </p>
          )}
          <p>
            {ready
              ? "The calculations balance and bank matching is complete. Closing rechecks these results and statement continuity before saving."
              : "Resolve the differences and unfinished bank matches before closing. Preview has not changed the books."}
          </p>
          <button
            disabled={busy || !ready}
            onClick={async () => {
              if (
                window.confirm(
                  `Close the statement from ${statement.startsOn} through ${statement.endsOn}? Backdated postings and changes to its bank rows will be protected.`,
                )
              )
                await act(
                  "/api/bank/reconciliations",
                  statement,
                  "Statement reconciliation saved. The period is closed.",
                );
            }}
          >
            Close statement
          </button>
        </section>
      )}
      <section className="card">
        <h2>Saved reconciliations</h2>
        {data.bankReconciliations.map((record) => (
          <article key={record.id}>
            <h3>
              {record.starts_on} to {record.ends_on} ·{" "}
              {record.status === "CLOSED" ? "Closed" : "Reopened"}
            </h3>
            <p>
              Statement closing balance:{" "}
              {dollars(cents(record.closing_balance))}. Closed by{" "}
              {record.closed_by} on {record.closed_at.slice(0, 10)}.
            </p>
            {record.reopen_reason && (
              <p>Reopening reason: {record.reopen_reason}</p>
            )}
            <details>
              <summary>View saved calculation</summary>
              <Summary
                result={JSON.parse(record.snapshot) as ReconciliationPreview}
              />
            </details>
            {canReopen && latest?.id === record.id && (
              <form
                onSubmit={async (event) => {
                  event.preventDefault();
                  if (
                    window.confirm(
                      `Reopen the period ending ${record.ends_on}? The original snapshot will remain in history.`,
                    )
                  ) {
                    if (
                      await act(
                        `/api/bank/reconciliations/${record.id}/reopen`,
                        { version: record.version, reason },
                        "Statement reopened. The original calculation is retained.",
                      )
                    )
                      setReason("");
                  }
                }}
              >
                <label>
                  Reopening reason
                  <input
                    required
                    maxLength={240}
                    disabled={busy}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <button className="secondary" disabled={busy || !reason.trim()}>
                  Reopen latest statement
                </button>
              </form>
            )}
          </article>
        ))}
        {!data.bankReconciliations.length && (
          <p className="empty">No statements closed yet.</p>
        )}
      </section>
    </>
  );
}
