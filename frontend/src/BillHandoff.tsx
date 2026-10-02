import { useState } from "react";
import { cents, dollars, today } from "./money";

type Props = {
  accrual: {
    id: string;
    posted_on: string;
    memo: string;
    amount: string;
    account_code: string;
  };
  category: string;
  vendors: { id: string; name: string }[];
  busy: boolean;
  act: (path: string, body: object, success: string) => Promise<boolean>;
  close: () => void;
};

export function BillHandoff({
  accrual,
  category,
  vendors,
  busy,
  act,
  close,
}: Props) {
  const initialDate = today() < accrual.posted_on ? accrual.posted_on : today();
  const [date, setDate] = useState(initialDate);
  const [due, setDue] = useState(initialDate);
  const [vendor, setVendor] = useState(vendors[0]?.id ?? "");
  const [reference, setReference] = useState("");
  const [description, setDescription] = useState(accrual.memo);
  const [amount, setAmount] = useState(String(accrual.amount));
  const [error, setError] = useState("");
  const validAmount =
    /^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(amount) && cents(amount) > 0n;
  const estimate = dollars(cents(accrual.amount));
  return (
    <section className="card">
      <h2>Receive the supplier bill</h2>
      <p>
        {accrual.memo} · Estimate posted {accrual.posted_on} · {estimate}
      </p>
      <p>
        Enter the actual bill for this estimate. Posting reverses the whole
        estimate and records the bill on the bill date. The original category is
        retained: {category}. Earlier reports stay unchanged when the bill is
        dated later.
      </p>
      <p>
        This creates an unpaid bill. Record its payment separately in Bills.
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
          if (!validAmount) {
            setError(
              "Enter a positive bill amount with at most two decimal places.",
            );
            return;
          }
          const done = await act(
            `/api/accruals/${accrual.id}/bill`,
            {
              vendorId: vendor,
              reference,
              description,
              issuedOn: date,
              dueOn: due,
              amount,
            },
            "Supplier bill posted. Estimate reversed and linked.",
          );
          // Keep the form intact when the command succeeds but its refresh is interrupted.
          if (done) close();
        }}
      >
        <fieldset className="owner-fields" disabled={busy}>
          <label>
            Supplier for this bill
            <select
              aria-label="Supplier for this bill"
              value={vendor}
              onChange={(e) => setVendor(e.target.value)}
              required
            >
              {vendors.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Supplier bill reference
            <input
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              maxLength={80}
              required
            />
          </label>
          <label>
            Supplier bill date
            <input
              type="date"
              min={accrual.posted_on}
              max="9999-12-31"
              value={date}
              onChange={(e) => {
                setDate(e.target.value);
                if (due < e.target.value) setDue(e.target.value);
              }}
              required
            />
          </label>
          <label>
            Supplier bill due date
            <input
              type="date"
              min={date}
              max="9999-12-31"
              value={due}
              onChange={(e) => setDue(e.target.value)}
              required
            />
          </label>
          <label>
            Supplier bill description
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={240}
              required
            />
          </label>
          <label>
            Actual bill amount (USD)
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
          <button type="submit">Post bill and reverse estimate</button>
          <button type="button" className="secondary" onClick={close}>
            Cancel bill handoff
          </button>
        </fieldset>
      </form>
      <div className="handoff-preview" aria-label="Bill handoff preview">
        <p>
          Reverse estimate: debit accrued expenses {estimate}; credit {category}{" "}
          {estimate}.
        </p>
        <p>
          Post bill: debit {category}{" "}
          {validAmount ? dollars(cents(amount)) : "Check amount"}; credit
          accounts payable{" "}
          {validAmount ? dollars(cents(amount)) : "Check amount"}.
        </p>
        <strong>
          Expense change on bill date:{" "}
          {validAmount
            ? dollars(cents(amount) - cents(accrual.amount))
            : "Check amount"}
        </strong>
        <p>A negative change reduces expenses. Cash stays unchanged.</p>
      </div>
    </section>
  );
}
