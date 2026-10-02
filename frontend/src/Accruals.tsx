import React, { useState } from "react";
import { cents, dollars, today } from "./money";

type Accrual = {
  id: string;
  posted_on: string;
  memo: string;
  account_code: string;
  amount: string;
  reversal_id: string | null;
  reversed_on: string | null;
  reversal_reason: string | null;
};
export type AccrualState = {
  accruals: Accrual[];
  expenseCategories: { code: string; name: string }[];
};
type Props = {
  data: AccrualState;
  busy: boolean;
  act: (path: string, body: object, success: string) => Promise<boolean>;
};

export function Accruals({ data, busy, act }: Props) {
  const [date, setDate] = useState(today());
  const [memo, setMemo] = useState("");
  const [account, setAccount] = useState(data.expenseCategories[0]?.code ?? "");
  const [amount, setAmount] = useState("");
  const [error, setError] = useState("");
  const [correcting, setCorrecting] = useState<Accrual | null>(null);
  const category = (code: string) =>
    `${code} · ${data.expenseCategories.find((a) => a.code === code)?.name ?? "Expense category"}`;
  let preview = "Enter a positive amount";
  if (/^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(amount) && cents(amount) > 0n)
    preview = dollars(cents(amount));
  return (
    <>
      <p className="intro">
        Record an operating expense already incurred when its supplier bill has
        not arrived. The entry increases expenses and accrued liabilities; it
        leaves cash and vendor aging unchanged.
      </p>
      <section className="card">
        <h2>Record an accrued expense</h2>
        <p>
          Identify the work received and the basis for the estimate in the memo.
          Use Bills when a supplier bill already exists.
        </p>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            setError("");
            if (
              !/^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(amount) ||
              cents(amount) <= 0n
            ) {
              setError(
                "Enter a positive amount with at most two decimal places.",
              );
              return;
            }
            const done = await act(
              "/api/accruals",
              { postedOn: date, memo, accountCode: account, amount },
              "Accrued expense posted.",
            );
            // Preserve the draft until the command and workspace refresh both succeed.
            if (done) {
              setMemo("");
              setAmount("");
              setDate(today());
            }
          }}
        >
          <fieldset className="owner-fields" disabled={busy}>
            <label>
              Accrual date
              <input
                type="date"
                min="0001-01-01"
                max="9999-12-31"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                required
              />
            </label>
            <label>
              Accrual category
              <select
                value={account}
                onChange={(e) => setAccount(e.target.value)}
                required
              >
                {data.expenseCategories.map((a) => (
                  <option key={a.code} value={a.code}>
                    {a.code} · {a.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Accrual memo
              <input
                value={memo}
                maxLength={240}
                onChange={(e) => setMemo(e.target.value)}
                required
              />
            </label>
            <label>
              Accrual amount (USD)
              <input
                value={amount}
                inputMode="decimal"
                pattern="[0-9]{1,12}(\.[0-9]{1,2})?"
                onChange={(e) => {
                  setAmount(e.target.value);
                  setError("");
                }}
                required
              />
            </label>
            <button type="submit">Post accrued expense</button>
          </fieldset>
        </form>
        <div className="adjustment-totals" aria-label="Accrual entry preview">
          <span>
            Debit {category(account)}: {preview}
          </span>
          <span>Credit 2100 · Accrued expenses: {preview}</span>
        </div>
      </section>
      <section className="card">
        <h2>Accrual history</h2>
        {data.accruals.length === 0 && <p>No accrued expenses recorded yet.</p>}
        {data.accruals.map((record) => (
          <article className="adjustment-record" key={record.id}>
            <h3>{record.memo}</h3>
            <p>
              Posted {record.posted_on} · {dollars(cents(record.amount))}
            </p>
            <p>
              {category(record.account_code)} · Debit expense / credit accrued
              expenses
            </p>
            {record.reversal_id ? (
              <>
                <p>
                  <strong>Reversed {record.reversed_on}</strong> ·{" "}
                  {record.reversal_reason}
                </p>
                <p>
                  Debit accrued expenses / credit original expense:{" "}
                  {dollars(cents(record.amount))}
                </p>
              </>
            ) : (
              <button
                type="button"
                className="secondary"
                disabled={busy}
                aria-label={`Reverse accrual ${record.memo}`}
                onClick={() => setCorrecting(record)}
              >
                Reverse accrued expense
              </button>
            )}
          </article>
        ))}
      </section>
      {correcting && (
        <section className="card">
          <h2>Reverse an accrued expense</h2>
          <p>
            {correcting.memo} · {correcting.posted_on} ·{" "}
            {dollars(cents(correcting.amount))}
          </p>
          <p>
            This offsets the original estimate on the reversal date and retains
            its history. Choose an open date on or after the original accrual. A
            later reversal preserves earlier reports.
          </p>
          <p>
            If the bill has arrived, review this reversal together with the bill
            entry to avoid counting the expense twice. This action does not
            create a bill or record a payment.
          </p>
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              const values = new FormData(event.currentTarget);
              const done = await act(
                `/api/accruals/${correcting.id}/reverse`,
                {
                  reversedOn: String(values.get("reversedOn")),
                  reason: String(values.get("reason")),
                },
                "Accrual reversed. Original entry retained.",
              );
              if (done) setCorrecting(null);
            }}
          >
            <fieldset className="owner-fields" disabled={busy}>
              <label>
                Accrual reversal date
                <input
                  type="date"
                  name="reversedOn"
                  min={correcting.posted_on}
                  max="9999-12-31"
                  defaultValue={
                    today() < correcting.posted_on
                      ? correcting.posted_on
                      : today()
                  }
                  required
                />
              </label>
              <label>
                Accrual reversal reason
                <input name="reason" maxLength={240} required />
              </label>
              <button type="submit">Confirm accrual reversal</button>
              <button
                type="button"
                className="secondary"
                onClick={() => setCorrecting(null)}
              >
                Cancel reversal
              </button>
            </fieldset>
          </form>
        </section>
      )}
    </>
  );
}
