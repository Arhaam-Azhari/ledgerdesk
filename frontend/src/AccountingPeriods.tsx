import { useEffect, useState } from "react";
import { cents, dollars, today } from "./money";
import type { ReportData } from "./Reports";
import type { CashData } from "./CashActivity";
import type { ReconciliationState } from "./BankReconciliation";

export type PeriodPreview = {
  startsOn: string;
  endsOn: string;
  bankReconciliationId: string | null;
  pendingPrepaidMonths: number;
  pendingDepreciationMonths: number;
  ready: boolean;
  reports: ReportData;
  cashActivity: CashData;
};
export type PeriodState = {
  accountingPeriodCloses: {
    id: string;
    starts_on: string;
    ends_on: string;
    review_note: string;
    snapshot: string;
    status: string;
    version: number;
    closed_by: string;
    closed_at: string;
    reopen_reason: string | null;
    reopened_by: string | null;
    reopened_at: string | null;
  }[];
};
function Totals({ review }: { review: PeriodPreview }) {
  const r = review.reports,
    c = review.cashActivity;
  const rows: [string, string][] = [
    ["Revenue", r.profitLoss.revenue],
    ["Expenses", r.profitLoss.expenses],
    ["Net profit", r.profitLoss.netProfit],
    ["Assets", r.balanceSheet.totalAssets],
    ["Liabilities", r.balanceSheet.totalLiabilities],
    ["Equity", r.balanceSheet.totalEquity],
    ["Balance sheet difference", r.balanceSheet.difference],
    ["Trial balance debits", r.trialBalance.debits],
    ["Trial balance credits", r.trialBalance.credits],
    ["Opening cash", c.openingCash],
    ["Cash receipts", c.receipts],
    ["Cash payments", c.payments],
    ["Closing cash", c.closingCash],
    ["Cash difference", c.difference],
    ["Unpaid customer balances", r.receivables.total],
    ["Unpaid supplier balances", r.payables.total],
  ];
  return (
    <div className="table-wrap">
      <table aria-label="Period report totals">
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
export function AccountingPeriods({
  data,
  busy,
  canWrite,
  preview,
  act,
}: {
  data: PeriodState & ReconciliationState;
  busy: boolean;
  canWrite: boolean;
  preview: (endsOn: string) => Promise<PeriodPreview | null>;
  act: (path: string, body: object, success: string) => Promise<boolean>;
}) {
  const latest = data.accountingPeriodCloses
    .filter((r) => r.status === "CLOSED")
    .sort((a, b) => b.ends_on.localeCompare(a.ends_on))[0];
  const nextBank = data.bankReconciliations
    .filter(
      (r) => r.status === "CLOSED" && (!latest || r.ends_on > latest.ends_on),
    )
    .sort((a, b) => a.ends_on.localeCompare(b.ends_on))[0];
  const date = new Date(
    Date.parse((latest?.ends_on ?? today()) + "T00:00:00Z") +
      (latest ? 86400000 : 0),
  );
  date.setUTCMonth(date.getUTCMonth() + 1, 0);
  const [end, setEnd] = useState(
    nextBank?.ends_on ?? date.toISOString().slice(0, 10),
  );
  const [review, setReview] = useState<PeriodPreview | null>(null);
  const [note, setNote] = useState("");
  const [reopening, setReopening] = useState("");
  const [reason, setReason] = useState("");
  useEffect(() => {
    setReview(null);
  }, [data]);
  return (
    <>
      <p className="intro">
        Review financial reports and supporting work before protecting an
        accounting period. Closing retains the review without creating a journal
        entry.
      </p>
      <section className="card">
        <h2>Period review dates</h2>
        <p>
          {latest
            ? `Accounting books are closed through ${latest.ends_on}. The next range starts the following day.`
            : "The first range includes the beginning of the recorded books, after any opening bank cutover."}
        </p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setReview(null);
            setReview(await preview(end));
          }}
        >
          <label>
            Accounting period end
            <input
              type="date"
              required
              min="0001-01-01"
              max="9999-12-31"
              disabled={busy}
              value={end}
              onChange={(e) => {
                setEnd(e.target.value);
                setReview(null);
              }}
            />
          </label>
          <button disabled={busy}>Preview accounting period</button>
        </form>
      </section>
      {review && (
        <section className="card">
          <h2>
            Accounting review · {review.startsOn} to {review.endsOn}
          </h2>
          <ul>
            <li>
              {review.bankReconciliationId
                ? "Bank statement at this month-end is closed."
                : "Close the bank statement ending at this month-end."}
            </li>
            <li>Prepaid months still due: {review.pendingPrepaidMonths}.</li>
            <li>
              Depreciation months still due: {review.pendingDepreciationMonths}.
            </li>
            <li>
              {review.ready
                ? "The prerequisites and report calculations pass."
                : "Resolve missing statement evidence, due schedule work or report differences before closing."}
            </li>
          </ul>
          <Totals review={review} />
          <p>
            Check supporting documents, estimates and outstanding bank items.
            Balanced totals do not establish that every business transaction was
            supplied. Unpaid invoices and bills can remain at close. Finish due
            schedule work before closing the supporting bank statement.
          </p>
          {canWrite && (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (
                  window.confirm(
                    `Close accounting books through ${review.endsOn}? Backdated changes and reopening supporting bank statements will be protected.`,
                  )
                ) {
                  if (
                    await act(
                      "/api/accounting-periods",
                      { endsOn: review.endsOn, reviewNote: note },
                      "Accounting period closed. The reviewed reports are retained.",
                    )
                  )
                    setNote("");
                }
              }}
            >
              <label>
                Period review note
                <input
                  required
                  maxLength={240}
                  disabled={busy}
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                />
              </label>
              <button disabled={busy || !review.ready || !note.trim()}>
                Close accounting period
              </button>
            </form>
          )}
        </section>
      )}
      <section className="card" aria-label="Saved accounting periods">
        <h2>Saved accounting periods</h2>
        {!data.accountingPeriodCloses.length && (
          <p>No accounting periods closed yet.</p>
        )}
        {data.accountingPeriodCloses.map((record) => (
          <article key={record.id}>
            <h3>
              {record.starts_on} to {record.ends_on} ·{" "}
              {record.status === "CLOSED" ? "Closed" : "Reopened"}
            </h3>
            <p>
              Closed by {record.closed_by} on {record.closed_at.slice(0, 10)}.{" "}
              {record.review_note}
            </p>
            {record.reopen_reason && (
              <p>
                Reopened by {record.reopened_by} on{" "}
                {record.reopened_at?.slice(0, 10)}: {record.reopen_reason}
              </p>
            )}
            <details>
              <summary>View retained period reports</summary>
              <Totals review={JSON.parse(record.snapshot) as PeriodPreview} />
            </details>
            {canWrite &&
              latest?.id === record.id &&
              (reopening !== record.id ? (
                <button
                  disabled={busy}
                  className="secondary"
                  onClick={() => {
                    setReopening(record.id);
                    setReason("");
                  }}
                >
                  Reopen latest accounting period
                </button>
              ) : (
                <form
                  onSubmit={async (e) => {
                    e.preventDefault();
                    if (
                      window.confirm(
                        `Reopen the accounting period ending ${record.ends_on}? Its original reports stay in history; bank statement protection still applies.`,
                      )
                    ) {
                      if (
                        await act(
                          `/api/accounting-periods/${record.id}/reopen`,
                          { version: record.version, reason },
                          "Accounting period reopened. Its original reports remain in history.",
                        )
                      ) {
                        setReopening("");
                        setReason("");
                      }
                    }
                  }}
                >
                  <label>
                    Accounting reopening reason
                    <input
                      required
                      maxLength={240}
                      disabled={busy}
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                    />
                  </label>
                  <button disabled={busy || !reason.trim()}>
                    Confirm accounting reopening
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() => setReopening("")}
                  >
                    Cancel reopening
                  </button>
                </form>
              ))}
          </article>
        ))}
      </section>
    </>
  );
}
