import React, { useRef, useState } from "react";
import { cents, dollars, today } from "./money";

type Header = {
  id: string;
  posted_on: string;
  memo: string;
  reversal_id: string | null;
  reversed_on: string | null;
  reversal_reason: string | null;
};
export type AdjustmentState = {
  adjustments: Header[];
  adjustmentLines: {
    id: string;
    adjustment_id: string;
    entry_date: string;
    entry_kind: "ORIGINAL" | "REVERSAL";
    account_code: string;
    name: string;
    debit: string;
    credit: string;
  }[];
  expenseCategories: { code: string; name: string }[];
};
type DraftLine = {
  id: number;
  accountCode: string;
  debit: string;
  credit: string;
};
type Props = {
  data: AdjustmentState;
  busy: boolean;
  act: (path: string, body: object, success: string) => Promise<boolean>;
};

export function Adjustments({ data, busy, act }: Props) {
  const initial = (): DraftLine[] =>
    data.expenseCategories
      .slice(0, 2)
      .map((a, i) => ({ id: i, accountCode: a.code, debit: "0", credit: "0" }));
  const [lines, setLines] = useState(initial);
  const nextLine = useRef(2);
  const [date, setDate] = useState(today());
  const [memo, setMemo] = useState("");
  const [error, setError] = useState("");
  const [correcting, setCorrecting] = useState<Header | null>(null);
  let debit = 0n,
    credit = 0n,
    validNumbers = true;
  try {
    for (const line of lines) {
      debit += cents(line.debit);
      credit += cents(line.credit);
    }
  } catch {
    validNumbers = false;
  }
  function edit(
    id: number,
    field: "accountCode" | "debit" | "credit",
    value: string,
  ) {
    setError("");
    setLines(
      lines.map((line) =>
        line.id === id ? { ...line, [field]: value } : line,
      ),
    );
  }
  return (
    <>
      <p className="intro">
        Move an already recorded expense between categories. Debits increase a
        category and credits reduce it. A balanced reclassification leaves total
        expenses, profit and cash unchanged.
      </p>
      <section className="card">
        <h2>Post an expense adjustment</h2>
        <p>
          Use a clear memo identifying the purchase and reason. Original
          purchase details stay in history. Bank, customer/vendor balances and
          owner equity use their existing workflows.
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
              !validNumbers ||
              lines.some(
                (l) =>
                  !/^[0-9]+(\.[0-9]{1,2})?$/.test(l.debit) ||
                  !/^[0-9]+(\.[0-9]{1,2})?$/.test(l.credit) ||
                  cents(l.debit) > 0n === cents(l.credit) > 0n,
              )
            ) {
              setError(
                "Each line needs a positive debit or credit and zero on the other side.",
              );
              return;
            }
            if (
              new Set(lines.map((l) => l.accountCode)).size !== lines.length
            ) {
              setError("Choose each category once.");
              return;
            }
            if (debit !== credit) {
              setError(
                "Debits and credits must balance exactly before posting.",
              );
              return;
            }
            const done = await act(
              "/api/adjustments",
              {
                postedOn: date,
                memo,
                lines: lines.map(({ accountCode, debit, credit }) => ({
                  accountCode,
                  debit,
                  credit,
                })),
              },
              "Adjustment posted.",
            );
            if (done) {
              setMemo("");
              setDate(today());
              setLines(initial());
              nextLine.current = 2;
            }
          }}
        >
          <fieldset className="adjustment-fields" disabled={busy}>
            <div className="form-row">
              <label>
                Adjustment date
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
                Adjustment memo
                <input
                  value={memo}
                  maxLength={300}
                  onChange={(e) => setMemo(e.target.value)}
                  required
                />
              </label>
            </div>
            {lines.map((line, i) => (
              <div className="adjustment-line" key={line.id}>
                <label>
                  Category line {i + 1}
                  <select
                    value={line.accountCode}
                    onChange={(e) =>
                      edit(line.id, "accountCode", e.target.value)
                    }
                  >
                    {data.expenseCategories.map((a) => (
                      <option value={a.code} key={a.code}>
                        {a.code} · {a.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Debit line {i + 1}
                  <input
                    value={line.debit}
                    inputMode="decimal"
                    pattern="[0-9]+(\.[0-9]{1,2})?"
                    onChange={(e) => edit(line.id, "debit", e.target.value)}
                    required
                  />
                </label>
                <label>
                  Credit line {i + 1}
                  <input
                    value={line.credit}
                    inputMode="decimal"
                    pattern="[0-9]+(\.[0-9]{1,2})?"
                    onChange={(e) => edit(line.id, "credit", e.target.value)}
                    required
                  />
                </label>
                <button
                  className="secondary"
                  type="button"
                  disabled={lines.length <= 2}
                  aria-label={`Remove line ${i + 1}`}
                  onClick={() => {
                    setLines(lines.filter((l) => l.id !== line.id));
                    setError("");
                  }}
                >
                  Remove
                </button>
              </div>
            ))}
            <button
              className="secondary"
              type="button"
              disabled={
                lines.length >= Math.min(20, data.expenseCategories.length)
              }
              onClick={() => {
                const category = data.expenseCategories.find(
                  (a) => !lines.some((l) => l.accountCode === a.code),
                );
                if (category)
                  setLines([
                    ...lines,
                    {
                      id: nextLine.current++,
                      accountCode: category.code,
                      debit: "0",
                      credit: "0",
                    },
                  ]);
              }}
            >
              Add line
            </button>
            <div className="adjustment-totals" aria-label="Adjustment totals">
              <span>
                Debits: {validNumbers ? dollars(debit) : "Check amounts"}
              </span>
              <span>
                Credits: {validNumbers ? dollars(credit) : "Check amounts"}
              </span>
              <strong>
                Difference:{" "}
                {validNumbers ? dollars(debit - credit) : "Check amounts"}
              </strong>
            </div>
            <button type="submit">Post adjustment</button>
          </fieldset>
        </form>
      </section>
      <section className="card">
        <h2>Adjustment history</h2>
        {data.adjustments.length === 0 && <p>No adjustments recorded yet.</p>}
        {data.adjustments.map((header) => (
          <article className="adjustment-record" key={header.id}>
            <h3>{header.memo}</h3>
            <p>Posted {header.posted_on}</p>
            {header.reversal_id ? (
              <p>
                <strong>Reversed {header.reversed_on}</strong> ·{" "}
                {header.reversal_reason}
              </p>
            ) : (
              <button
                className="secondary"
                disabled={busy}
                aria-label={`Reverse adjustment ${header.memo}`}
                onClick={() => setCorrecting(header)}
              >
                Reverse mistaken adjustment
              </button>
            )}
            {(["ORIGINAL", "REVERSAL"] as const).map((kind) => {
              const rows = data.adjustmentLines.filter(
                (l) => l.adjustment_id === header.id && l.entry_kind === kind,
              );
              return (
                rows.length > 0 && (
                  <div key={kind}>
                    <h4>
                      {kind === "ORIGINAL"
                        ? "Original lines"
                        : "Reversal lines"}
                    </h4>
                    <div className="table-wrap">
                      <table className="adjustment-history">
                        <thead>
                          <tr>
                            <th>Date</th>
                            <th>Category</th>
                            <th>Debit</th>
                            <th>Credit</th>
                          </tr>
                        </thead>
                        <tbody>
                          {rows.map((l) => (
                            <tr key={l.id}>
                              <td>{l.entry_date}</td>
                              <td>
                                {l.account_code} · {l.name}
                              </td>
                              <td>{dollars(cents(l.debit))}</td>
                              <td>{dollars(cents(l.credit))}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )
              );
            })}
          </article>
        ))}
      </section>
      {correcting && (
        <section className="card">
          <h2>Reverse a mistaken adjustment</h2>
          <p>
            {correcting.memo} · {correcting.posted_on}
          </p>
          <p>
            The original remains in history. A reversal offsets every line on
            its own date. Choose an open period on or after the original date; a
            later correction preserves earlier reports.
          </p>
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              const form = event.currentTarget;
              const values = new FormData(form);
              const done = await act(
                `/api/adjustments/${correcting.id}/reverse`,
                {
                  reversedOn: String(values.get("reversedOn")),
                  reason: String(values.get("reason")),
                },
                "Adjustment reversed. Original lines retained.",
              );
              if (done) setCorrecting(null);
            }}
          >
            <fieldset className="owner-fields" disabled={busy}>
              <label>
                Adjustment reversal date
                <input
                  name="reversedOn"
                  type="date"
                  defaultValue={
                    today() < correcting.posted_on
                      ? correcting.posted_on
                      : today()
                  }
                  min={correcting.posted_on}
                  max="9999-12-31"
                  required
                />
              </label>
              <label>
                Adjustment reversal reason
                <input name="reason" maxLength={240} required />
              </label>
              <button type="submit">Reverse adjustment</button>
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
