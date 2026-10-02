import { useState } from "react";
import type { Expense } from "./Purchases";
import { cents, dollars, today } from "./money";
type Plan = {
  id: string;
  expense_id: string;
  funded_on: string;
  starts_on: string;
  months: number;
  memo: string;
  account_code: string;
  amount: string;
  vendor_name: string;
  description: string;
  cancellation_id: string | null;
  cancelled_on: string | null;
  cancellation_reason: string | null;
  cancelled_amount: string | null;
  correction_id: string | null;
  corrected_on: string | null;
  correction_reason: string | null;
};
type Period = {
  id: string;
  plan_id: string;
  period_on: string;
  amount: string;
  entry_id: string | null;
};
export type PrepaidState = {
  prepaidPlans: Plan[];
  prepaidPeriods: Period[];
  expenses: Expense[];
};
type Props = {
  data: PrepaidState;
  busy: boolean;
  openExpenses: () => void;
  act: (path: string, body: object, success: string) => Promise<boolean>;
};
// Use calendar arithmetic so month ends do not shift with the browser timezone.
function monthEnd(start: string, offset: number) {
  const [year, month] = start.split("-").map(Number),
    index = year * 12 + month - 1 + offset;
  const y = Math.floor(index / 12),
    m = (index % 12) + 1,
    leap = y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0);
  const day = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][
    m - 1
  ];
  return `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${day}`;
}
export function Prepaids({ data, busy, act, openExpenses }: Props) {
  const available = data.expenses.filter(
    (e) =>
      e.status === "POSTED" &&
      !data.prepaidPlans.some((p) => p.expense_id === e.id),
  );
  const [expenseId, setExpenseId] = useState("");
  const [startsOn, setStartsOn] = useState("");
  const [months, setMonths] = useState("3");
  const [memo, setMemo] = useState("");
  const [error, setError] = useState("");
  const [operation, setOperation] = useState<{
    plan: Plan;
    kind: "cancel" | "correct";
  } | null>(null);
  const expense = available.find((e) => e.id === expenseId),
    n = Number(months);
  const valid =
    !!expense &&
    /^\d{4}-(0[1-9]|1[0-2])-01$/.test(startsOn) &&
    startsOn >= expense.spent_on &&
    Number.isInteger(n) &&
    n >= 1 &&
    n <= 60 &&
    Number(startsOn.slice(0, 4)) >= 1 &&
    Number(monthEnd(startsOn, n - 1).slice(0, 4)) <= 9999 &&
    cents(expense.amount) / BigInt(n) > 0n;
  const preview = valid
    ? Array.from({ length: n }, (_, i) => ({
        date: monthEnd(startsOn, i),
        amount:
          i === n - 1
            ? cents(expense!.amount) -
              (cents(expense!.amount) / BigInt(n)) * BigInt(i)
            : cents(expense!.amount) / BigInt(n),
      }))
    : [];
  return (
    <>
      <p className="intro">
        Spread a purchase paid upfront over whole calendar months. Its payment
        and receipts stay in Expenses; this page defers its cost and recognizes
        it as the benefit is used.
      </p>
      <section className="card">
        <h2>Create a prepaid plan</h2>
        <p>
          The original payment date must be open. Start on the first day of a
          month on or after payment. Partial months and unpaid bills are not
          supported.
        </p>
        {available.length === 0 && (
          <p>
            No unused paid expenses available. Record a purchase in Expenses
            first.
          </p>
        )}
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={openExpenses}
        >
          View expenses and receipts
        </button>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setError("");
            if (!valid) {
              setError(
                "Choose an eligible purchase, a first-of-month start and one to sixty months with at least one cent per month.",
              );
              return;
            }
            if (
              await act(
                "/api/prepaid",
                { expenseId, startsOn, months: n, memo },
                "Prepaid plan created. Original payment retained.",
              )
            ) {
              setExpenseId("");
              setMemo("");
              setStartsOn("");
            }
          }}
        >
          <fieldset
            className="owner-fields"
            disabled={busy || available.length === 0}
          >
            <label>
              Paid purchase
              <select
                value={expenseId}
                onChange={(e) => {
                  setExpenseId(e.target.value);
                  setStartsOn("");
                }}
                required
              >
                <option value="">Choose a purchase</option>
                {available.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.spent_on} · {e.vendor_name} · {e.description} ·{" "}
                    {dollars(cents(e.amount))}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Benefit start
              <input
                type="date"
                min={expense?.spent_on ?? "0001-01-01"}
                max="9999-12-01"
                value={startsOn}
                onChange={(e) => setStartsOn(e.target.value)}
                required
              />
            </label>
            <label>
              Number of months
              <input
                type="number"
                min="1"
                max="60"
                step="1"
                value={months}
                onChange={(e) => setMonths(e.target.value)}
                required
              />
            </label>
            <label>
              Prepaid memo
              <input
                value={memo}
                onChange={(e) => setMemo(e.target.value)}
                maxLength={240}
                required
              />
            </label>
            <button type="submit">Create prepaid plan</button>
          </fieldset>
        </form>
        {expense && (
          <p>
            Setup debits 1300 · Prepaid expenses and credits {expense.category}{" "}
            for {dollars(cents(expense.amount))}. Cash is unchanged.
          </p>
        )}
        {preview.length > 0 && (
          <div className="table-wrap">
            <table aria-label="Prepaid schedule preview">
              <thead>
                <tr>
                  <th>Month end</th>
                  <th>Expense (USD)</th>
                </tr>
              </thead>
              <tbody>
                {preview.map((p) => (
                  <tr key={p.date}>
                    <td>{p.date}</td>
                    <td>{dollars(p.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <section className="card">
        <h2>Prepaid plans and recognition</h2>
        {data.prepaidPlans.length === 0 && (
          <p>No prepaid plans recorded yet.</p>
        )}
        {data.prepaidPlans.map((plan) => {
          const periods = data.prepaidPeriods.filter(
              (p) => p.plan_id === plan.id,
            ),
            next = periods.find((p) => !p.entry_id),
            terminal = !!plan.cancellation_id || !!plan.correction_id;
          const remaining = periods
            .filter((p) => !p.entry_id)
            .reduce((sum, p) => sum + cents(p.amount), 0n);
          return (
            <article className="adjustment-record" key={plan.id}>
              <h3>{plan.memo}</h3>
              <p>
                {plan.vendor_name} · {plan.description} · Paid {plan.funded_on}{" "}
                · {dollars(cents(plan.amount))}
              </p>
              <p>
                Category {plan.account_code} · {plan.months} months from{" "}
                {plan.starts_on}
              </p>
              <p>
                <strong>
                  {plan.correction_id
                    ? "Corrected to direct expense"
                    : plan.cancellation_id
                      ? "Cancelled"
                      : next
                        ? "Active"
                        : "Fully recognized"}
                </strong>{" "}
                · Remaining prepaid asset: {dollars(terminal ? 0n : remaining)}
              </p>
              {plan.correction_id && (
                <p>
                  Corrected {plan.corrected_on}: {plan.correction_reason}.
                  Original expense restored; history retained.
                </p>
              )}
              {plan.cancellation_id && (
                <p>
                  Cancelled {plan.cancelled_on}: {plan.cancellation_reason}.
                  Remaining {dollars(cents(plan.cancelled_amount!))} expensed on
                  that date.
                </p>
              )}
              <div className="table-wrap">
                <table aria-label={`Schedule for ${plan.memo}`}>
                  <thead>
                    <tr>
                      <th>Month end</th>
                      <th>Amount (USD)</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {periods.map((p) => (
                      <tr key={p.id}>
                        <td>{p.period_on}</td>
                        <td>{dollars(cents(p.amount))}</td>
                        <td>
                          {p.entry_id
                            ? "Recognized"
                            : terminal
                              ? "Not posted · plan ended"
                              : "Scheduled"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!terminal && next && (
                <>
                  <p>
                    Recognition debits {plan.account_code} and credits Prepaid
                    expenses. Post months in order on an open scheduled date.
                    Future schedule rows do not affect reports.
                  </p>
                  <div className="button-row">
                    <button
                      disabled={busy}
                      aria-label={`Recognize ${plan.memo} on ${next.period_on}`}
                      onClick={() =>
                        act(
                          `/api/prepaid/${plan.id}/recognize`,
                          { periodOn: next.period_on },
                          "Prepaid month recognized.",
                        )
                      }
                    >
                      Recognize {next.period_on} · {dollars(cents(next.amount))}
                    </button>
                    <button
                      className="secondary"
                      disabled={busy}
                      aria-label={`Cancel remaining benefit for ${plan.memo}`}
                      onClick={() => setOperation({ plan, kind: "cancel" })}
                    >
                      End remaining benefit
                    </button>
                    {!periods.some((p) => p.entry_id) && (
                      <button
                        className="secondary"
                        disabled={busy}
                        aria-label={`Correct accidental plan ${plan.memo}`}
                        onClick={() => setOperation({ plan, kind: "correct" })}
                      >
                        Correct accidental plan
                      </button>
                    )}
                  </div>
                </>
              )}
            </article>
          );
        })}
      </section>
      {operation && (
        <PlanAction
          key={`${operation.kind}-${operation.plan.id}`}
          {...operation}
          periods={data.prepaidPeriods.filter(
            (p) => p.plan_id === operation.plan.id,
          )}
          busy={busy}
          act={act}
          close={() => setOperation(null)}
        />
      )}
    </>
  );
}
function PlanAction({
  plan,
  kind,
  periods,
  busy,
  act,
  close,
}: {
  plan: Plan;
  kind: "cancel" | "correct";
  periods: Period[];
  busy: boolean;
  act: Props["act"];
  close: () => void;
}) {
  const earliest = periods
    .filter((p) => p.entry_id)
    .reduce(
      (date, p) => (p.period_on > date ? p.period_on : date),
      plan.funded_on,
    );
  const [date, setDate] = useState(today() < earliest ? earliest : today()),
    [reason, setReason] = useState("");
  const remaining = periods
    .filter((p) => !p.entry_id)
    .reduce((sum, p) => sum + cents(p.amount), 0n);
  return (
    <section className="card">
      <h2>
        {kind === "cancel"
          ? "End a prepaid benefit"
          : "Correct an accidental prepaid plan"}
      </h2>
      <p>{plan.memo}</p>
      <p>
        {kind === "cancel"
          ? `Expense the remaining ${dollars(remaining)} on the chosen open date. Earlier reports stay unchanged. This stops future recognition and does not record a supplier refund.`
          : `Offset the setup on ${plan.funded_on} and restore the direct expense. That date must be open. The purchase and bank match stay intact. This retained plan cannot be replaced on the same purchase.`}
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const done = await act(
            `/api/prepaid/${plan.id}/${kind}`,
            kind === "cancel" ? { cancelledOn: date, reason } : { reason },
            kind === "cancel"
              ? "Prepaid benefit ended. Remaining balance expensed."
              : "Prepaid plan corrected. Direct expense restored.",
          );
          // Preserve the form if the command succeeds but its workspace refresh is interrupted.
          if (done) close();
        }}
      >
        <fieldset className="owner-fields" disabled={busy}>
          {kind === "cancel" && (
            <label>
              Benefit cancellation date
              <input
                type="date"
                min={earliest}
                max="9999-12-31"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                required
              />
            </label>
          )}
          <label>
            {kind === "cancel"
              ? "Benefit cancellation reason"
              : "Prepaid correction reason"}
            <input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={240}
              required
            />
          </label>
          <button type="submit">
            {kind === "cancel"
              ? "Confirm benefit cancellation"
              : "Confirm prepaid correction"}
          </button>
          <button type="button" className="secondary" onClick={close}>
            Keep this plan
          </button>
        </fieldset>
      </form>
    </section>
  );
}
