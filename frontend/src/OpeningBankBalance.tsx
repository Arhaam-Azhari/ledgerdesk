import { useState } from "react";
import { cents, dollars, today } from "./money";

export type OpeningState = {
  openingBankBalances: {
    id: string;
    as_of: string;
    balance: string;
    memo: string;
  }[];
};
export function OpeningBankBalance({
  data,
  busy,
  act,
}: {
  data: OpeningState & {
    ledger: unknown[];
    bankImports: unknown[];
    bankReconciliations: unknown[];
  };
  busy: boolean;
  act: (path: string, body: object, success: string) => Promise<boolean>;
}) {
  const [asOf, setAsOf] = useState(today());
  const [balance, setBalance] = useState("");
  const [memo, setMemo] = useState("");
  const opening = data.openingBankBalances[0];
  const started =
    data.ledger.length > 0 ||
    data.bankImports.length > 0 ||
    data.bankReconciliations.length > 0;
  const validAmount = /^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(balance);
  return (
    <>
      <p className="intro">
        Carry a cleared bank balance into new books before recording business
        activity.
      </p>
      <section className="card">
        <h2>
          {opening
            ? "Recorded opening bank balance"
            : "Opening bank balance setup"}
        </h2>
        {opening ? (
          <>
            <p>
              Prior books end on {opening.as_of}. Cleared bank balance:{" "}
              {dollars(cents(opening.balance))}.
            </p>
            <p>{opening.memo}</p>
            <p>
              This opening is retained. New postings and bank imports must be
              dated after {opening.as_of}. The first statement starts the
              following day and carries this amount.
            </p>
          </>
        ) : started ? (
          <p>
            Setup is unavailable because journal postings, bank imports or
            reconciliations already exist. The opening must be recorded before
            any of these.
          </p>
        ) : (
          <>
            <p>
              Use the last day covered by your prior books and the bank balance
              already cleared on that date. Do not include outstanding cheques
              or deposits. This setup does not import unpaid invoices, bills or
              other opening accounts.
            </p>
            <p>
              The balance goes to bank and opening balance equity. It creates no
              sales, expenses or current owner transfer. Zero is allowed to set
              the starting date.
            </p>
            <form
              onSubmit={async (event) => {
                event.preventDefault();
                if (
                  window.confirm(
                    `Record ${dollars(cents(balance))} as the cleared bank balance on ${asOf}? This opening cannot be edited or reversed. Later postings must be after this date.`,
                  )
                )
                  await act(
                    "/api/opening-bank-balance",
                    { asOf, balance, memo },
                    "Opening bank balance recorded.",
                  );
              }}
            >
              <label>
                Prior books end date
                <input
                  type="date"
                  min="0001-01-01"
                  max="9999-12-30"
                  required
                  disabled={busy}
                  value={asOf}
                  onChange={(e) => setAsOf(e.target.value)}
                />
              </label>
              <label>
                Cleared bank balance (USD)
                <input
                  inputMode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,2})?"
                  required
                  disabled={busy}
                  value={balance}
                  onChange={(e) => setBalance(e.target.value)}
                />
              </label>
              <label>
                Opening balance note
                <input
                  maxLength={240}
                  required
                  disabled={busy}
                  value={memo}
                  onChange={(e) => setMemo(e.target.value)}
                />
              </label>
              {validAmount && (
                <p>
                  Bank and opening equity: {dollars(cents(balance))}. Check the
                  date and amount before confirming; this opening cannot be
                  changed.
                </p>
              )}
              <button disabled={busy || !validAmount || !memo.trim()}>
                Record opening balance
              </button>
            </form>
          </>
        )}
      </section>
    </>
  );
}
