import React from "react";
import { cents, dollars, today } from "./money";

export type EquityState = {
  equityTransactions: {
    id: string;
    kind: "CONTRIBUTION" | "DRAWING";
    posted_on: string;
    memo: string;
    amount: string;
  }[];
};
type Props = {
  data: EquityState;
  busy: boolean;
  act: (
    path: string,
    body: object,
    success: string,
    form?: HTMLFormElement,
  ) => Promise<boolean>;
};

export function OwnerEquity({ data, busy, act }: Props) {
  const contributions = data.equityTransactions
    .filter((t) => t.kind === "CONTRIBUTION")
    .reduce((total, t) => total + cents(t.amount), 0n);
  const drawings = data.equityTransactions
    .filter((t) => t.kind === "DRAWING")
    .reduce((total, t) => total + cents(t.amount), 0n);
  return (
    <>
      <p className="intro">
        Record money the owner puts into the business or takes out for personal
        use. These transfers change bank and equity without changing profit.
      </p>
      <section className="metrics">
        <article>
          <span>Owner contributions</span>
          <h2>{dollars(contributions)}</h2>
          <small>All recorded dates</small>
        </article>
        <article>
          <span>Owner withdrawals</span>
          <h2>{dollars(drawings)}</h2>
          <small>All recorded dates</small>
        </article>
        <article>
          <span>Net owner funding</span>
          <h2>{dollars(contributions - drawings)}</h2>
          <small>Contributions less withdrawals, excluding earnings</small>
        </article>
      </section>
      <section className="card">
        <h2>Record an owner transfer</h2>
        <p>
          Record a transfer that has already happened. This does not move money.
          Check the date, amount and direction before recording; posted
          transfers stay in history.
        </p>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            const form = event.currentTarget;
            const values = new FormData(form);
            void act(
              "/api/equity",
              {
                kind: String(values.get("kind")),
                postedOn: String(values.get("postedOn")),
                memo: String(values.get("memo")),
                amount: String(values.get("amount")),
              },
              "Owner transfer recorded.",
              form,
            );
          }}
        >
          <fieldset className="owner-fields" disabled={busy}>
            <label>
              Transfer type
              <select name="kind" required>
                <option value="CONTRIBUTION">Contribution to business</option>
                <option value="DRAWING">Withdrawal for owner</option>
              </select>
            </label>
            <label>
              Transfer date
              <input
                name="postedOn"
                type="date"
                defaultValue={today()}
                min="0001-01-01"
                max="9999-12-31"
                required
              />
            </label>
            <label>
              Transfer memo
              <input
                name="memo"
                maxLength={240}
                placeholder="Personal savings for business setup"
                required
              />
            </label>
            <label>
              Transfer amount (USD)
              <input
                name="amount"
                inputMode="decimal"
                pattern="[0-9]+(\.[0-9]{1,2})?"
                placeholder="1000.00"
                required
              />
            </label>
            <button type="submit">Record transfer</button>
          </fieldset>
        </form>
      </section>
      <section className="card">
        <h2>Owner transfer history</h2>
        {data.equityTransactions.length === 0 ? (
          <p>No owner transfers recorded yet.</p>
        ) : (
          <div className="table-wrap">
            <table className="owner-history">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Type</th>
                  <th>Memo</th>
                  <th>Amount</th>
                </tr>
              </thead>
              <tbody>
                {data.equityTransactions.map((t) => (
                  <tr key={t.id}>
                    <td>{t.posted_on}</td>
                    <td>
                      {t.kind === "CONTRIBUTION"
                        ? "Contribution"
                        : "Withdrawal"}
                    </td>
                    <td>{t.memo}</td>
                    <td>{dollars(cents(t.amount))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
