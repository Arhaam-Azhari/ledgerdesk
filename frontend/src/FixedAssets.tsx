import { useState } from "react";
import type { Expense } from "./Purchases";
import { cents, dollars, today } from "./money";
type Asset = {
  id: string;
  expense_id: string;
  funded_on: string;
  in_service_on: string;
  months: number;
  name: string;
  account_code: string;
  cost: string;
  residual_value: string;
  vendor_name: string;
  description: string;
  retirement_id: string | null;
  retired_on: string | null;
  retirement_reason: string | null;
  retirement_loss: string | null;
  correction_id: string | null;
  corrected_on: string | null;
  correction_reason: string | null;
};
type Period = {
  id: string;
  asset_id: string;
  period_on: string;
  amount: string;
  entry_id: string | null;
};
export type AssetState = {
  fixedAssets: Asset[];
  assetPeriods: Period[];
  expenses: Expense[];
  prepaidPlans: { expense_id: string; correction_id: string | null }[];
};
type Props = {
  data: AssetState;
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
export function FixedAssets({ data, busy, act, openExpenses }: Props) {
  const available = data.expenses.filter(
    (e) =>
      e.status === "POSTED" &&
      !data.fixedAssets.some((p) => p.expense_id === e.id) &&
      !data.prepaidPlans.some((p) => p.expense_id === e.id && !p.correction_id),
  );
  const [expenseId, setExpenseId] = useState("");
  const [startsOn, setStartsOn] = useState("");
  const [months, setMonths] = useState("36");
  const [memo, setMemo] = useState("");
  const [residualValue, setResidualValue] = useState("0");
  const [error, setError] = useState("");
  const [operation, setOperation] = useState<{
    plan: Asset;
    kind: "retire" | "correct";
  } | null>(null);
  const expense = available.find((e) => e.id === expenseId),
    n = Number(months);
  const residualValid = /^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(residualValue);
  const residual = residualValid ? cents(residualValue) : 0n;
  const depreciable = expense ? cents(expense.amount) - residual : 0n;
  const valid =
    !!expense &&
    /^\d{4}-(0[1-9]|1[0-2])-01$/.test(startsOn) &&
    startsOn >= expense.spent_on &&
    Number.isInteger(n) &&
    n >= 1 &&
    n <= 600 &&
    Number(startsOn.slice(0, 4)) >= 1 &&
    monthEnd(startsOn, n - 1).length === 10 &&
    residualValid &&
    depreciable > 0n &&
    depreciable / BigInt(n) > 0n;
  const preview = valid
    ? Array.from({ length: n }, (_, i) => ({
        date: monthEnd(startsOn, i),
        amount:
          i === n - 1
            ? depreciable - (depreciable / BigInt(n)) * BigInt(i)
            : depreciable / BigInt(n),
      }))
    : [];
  return (
    <>
      <p className="intro">
        Capitalize equipment paid upfront and recognize its depreciation over
        whole calendar months. Its payment and receipts stay in Expenses. Cost
        and accumulated depreciation remain separate in the books.
      </p>
      <section className="card">
        <h2>Register a fixed asset</h2>
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
                "Choose an eligible purchase, a first-of-month start and one to six hundred months and a residual value below cost, leaving at least one cent of depreciation per month.",
              );
              return;
            }
            if (
              await act(
                "/api/assets",
                {
                  expenseId,
                  inServiceOn: startsOn,
                  months: n,
                  name: memo,
                  residualValue,
                },
                "Fixed asset created. Original payment retained.",
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
                aria-label="Paid purchase"
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
              In-service start
              <input
                type="date"
                min={expense?.spent_on ?? "0001-01-01"}
                max="9999-12-01"
                value={startsOn}
                onChange={(e) => {
                  setStartsOn(e.target.value);
                  setError("");
                }}
                required
              />
            </label>
            <label>
              Useful life (months)
              <input
                type="number"
                min="1"
                max="600"
                step="1"
                value={months}
                onChange={(e) => {
                  setMonths(e.target.value);
                  setError("");
                }}
                required
              />
            </label>
            <label>
              Asset name
              <input
                value={memo}
                onChange={(e) => setMemo(e.target.value)}
                maxLength={240}
                required
              />
            </label>
            <label>
              Residual value (USD)
              <input
                value={residualValue}
                inputMode="decimal"
                pattern="[0-9]{1,12}(\.[0-9]{1,2})?"
                onChange={(e) => {
                  setResidualValue(e.target.value);
                  setError("");
                }}
                required
              />
            </label>
            <button type="submit">Register fixed asset</button>
          </fieldset>
        </form>
        {expense && (
          <p>
            Setup debits 1500 · Equipment at cost and credits {expense.category}{" "}
            for {dollars(cents(expense.amount))}. Cash is unchanged.
          </p>
        )}
        {preview.length > 0 && (
          <div className="table-wrap">
            <table aria-label="Depreciation schedule preview">
              <thead>
                <tr>
                  <th>Month end</th>
                  <th>Depreciation (USD)</th>
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
        <h2>Asset register and depreciation</h2>
        {data.fixedAssets.length === 0 && <p>No fixed assets recorded yet.</p>}
        {data.fixedAssets.map((plan) => {
          const periods = data.assetPeriods.filter(
              (p) => p.asset_id === plan.id,
            ),
            next = periods.find((p) => !p.entry_id),
            terminal = !!plan.retirement_id || !!plan.correction_id;
          const accumulated = periods
            .filter((p) => p.entry_id)
            .reduce((sum, p) => sum + cents(p.amount), 0n);
          const remaining = cents(plan.cost) - accumulated;
          return (
            <article className="adjustment-record" key={plan.id}>
              <h3>{plan.name}</h3>
              <p>
                {plan.vendor_name} · {plan.description} · Paid {plan.funded_on}{" "}
                · {dollars(cents(plan.cost))}
              </p>
              <p>
                Residual value: {dollars(cents(plan.residual_value))} ·
                Depreciation posted: {dollars(accumulated)}
              </p>
              <p>
                Original category {plan.account_code} · {plan.months} months
                from {plan.in_service_on}
              </p>
              <p>
                <strong>
                  {plan.correction_id
                    ? "Corrected to direct expense"
                    : plan.retirement_id
                      ? "Retired"
                      : next
                        ? "Active"
                        : "Fully depreciated"}
                </strong>{" "}
                · Net book value: {dollars(terminal ? 0n : remaining)}
              </p>
              {plan.correction_id && (
                <p>
                  Corrected {plan.corrected_on}: {plan.correction_reason}.
                  Original expense restored; history retained.
                </p>
              )}
              {plan.retirement_id && (
                <p>
                  Retired {plan.retired_on}: {plan.retirement_reason}.
                  Retirement loss {dollars(cents(plan.retirement_loss!))} on
                  that date; cost and accumulated depreciation removed.
                </p>
              )}
              <div className="table-wrap">
                <table aria-label={`Schedule for ${plan.name}`}>
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
                            ? "Depreciated"
                            : terminal
                              ? "Not posted · asset ended"
                              : "Scheduled"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!terminal && (
                <>
                  <p>
                    Depreciation debits 5600 and credits 1590 · Accumulated
                    depreciation. Post months in order on an open scheduled
                    date. Future schedule rows do not affect reports.
                  </p>
                  <div className="button-row">
                    {next && (
                      <button
                        disabled={busy}
                        aria-label={`Depreciate ${plan.name} on ${next.period_on}`}
                        onClick={() =>
                          act(
                            `/api/assets/${plan.id}/depreciate`,
                            { periodOn: next.period_on },
                            "Depreciation posted.",
                          )
                        }
                      >
                        Depreciate {next.period_on} ·{" "}
                        {dollars(cents(next.amount))}
                      </button>
                    )}
                    <button
                      className="secondary"
                      disabled={busy}
                      aria-label={`Retire asset for ${plan.name}`}
                      onClick={() => setOperation({ plan, kind: "retire" })}
                    >
                      Retire without proceeds
                    </button>
                    {!periods.some((p) => p.entry_id) && (
                      <button
                        className="secondary"
                        disabled={busy}
                        aria-label={`Correct accidental asset ${plan.name}`}
                        onClick={() => setOperation({ plan, kind: "correct" })}
                      >
                        Correct accidental asset
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
        <AssetAction
          key={`${operation.kind}-${operation.plan.id}`}
          {...operation}
          periods={data.assetPeriods.filter(
            (p) => p.asset_id === operation.plan.id,
          )}
          busy={busy}
          act={act}
          close={() => setOperation(null)}
        />
      )}
    </>
  );
}
function AssetAction({
  plan,
  kind,
  periods,
  busy,
  act,
  close,
}: {
  plan: Asset;
  kind: "retire" | "correct";
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
  const accumulated = periods
    .filter((p) => p.entry_id)
    .reduce((sum, p) => sum + cents(p.amount), 0n);
  const remaining = cents(plan.cost) - accumulated;
  return (
    <section className="card">
      <h2>
        {kind === "retire"
          ? "Retire equipment without proceeds"
          : "Correct accidental capitalization"}
      </h2>
      <p>{plan.name}</p>
      <p>
        {kind === "retire"
          ? `Remove equipment cost ${dollars(cents(plan.cost))} and accumulated depreciation ${dollars(accumulated)}; record the remaining ${dollars(remaining)} as a retirement loss. Post any earlier scheduled depreciation first. The date must be open and on or after all posted depreciation. No sale proceeds, refund or bank payment are recorded.`
          : `Offset the setup on ${plan.funded_on} and restore the direct expense. That date must be open. The purchase and bank match stay intact. This retained asset cannot be replaced on the same purchase.`}
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const done = await act(
            `/api/assets/${plan.id}/${kind}`,
            kind === "retire" ? { retiredOn: date, reason } : { reason },
            kind === "retire"
              ? "Asset retired. Remaining book value recorded as a loss."
              : "Asset corrected. Direct expense restored.",
          );
          // Preserve the form if the command succeeds but its workspace refresh is interrupted.
          if (done) close();
        }}
      >
        <fieldset className="owner-fields" disabled={busy}>
          {kind === "retire" && (
            <label>
              Asset retirement date
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
            {kind === "retire"
              ? "Asset retirement reason"
              : "Asset correction reason"}
            <input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={240}
              required
            />
          </label>
          <button type="submit">
            {kind === "retire"
              ? "Confirm asset retirement"
              : "Confirm asset correction"}
          </button>
          <button type="button" className="secondary" onClick={close}>
            Keep this asset
          </button>
        </fieldset>
      </form>
    </section>
  );
}
