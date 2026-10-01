import { useEffect, useState } from "react";
import { cents, dollars } from "./money";

export type BankCandidate = {
  line_id: string;
  entry_date: string;
  memo: string;
  amount: string;
  kind: string;
  days_apart: string;
  near_date: boolean;
};
export type MatchState = {
  bankMatches: {
    id: string;
    transaction_id: string;
    line_id: string;
    matched_at: string;
    entry_date: string;
    memo: string;
    amount: string;
  }[];
  bankMatchEvents: {
    id: string;
    match_id: string;
    transaction_id: string;
    line_id: string;
    action: string;
    occurred_at: string;
  }[];
};
type Transaction = {
  id: string;
  external_id: string;
  posted_on: string;
  description: string;
  amount: string;
};
type Props = {
  data: MatchState & { bankTransactions: Transaction[] };
  busy: boolean;
  candidates: (id: string) => Promise<BankCandidate[] | null>;
  act: (path: string, body: object, success: string) => Promise<boolean>;
};

export function BankMatching({ data, busy, candidates, act }: Props) {
  const [selected, setSelected] = useState("");
  const [entries, setEntries] = useState<BankCandidate[] | null>(null);
  const [lineId, setLineId] = useState("");
  const transaction = data.bankTransactions.find((row) => row.id === selected);
  const choice = entries?.find((row) => row.line_id === lineId);
  useEffect(() => {
    setSelected("");
    setEntries(null);
    setLineId("");
  }, [data]);

  async function review(id: string) {
    setSelected(id);
    setEntries(null);
    setLineId("");
    const result = await candidates(id);
    if (result) setEntries(result);
    else setSelected("");
  }

  return (
    <>
      <p className="intro">
        Link statement evidence to an already recorded customer payment, bill
        payment, or direct expense. Matching and undo leave the ledger
        unchanged.
      </p>
      <section className="card">
        <h2>Bank transactions to review</h2>
        <p>
          A match links one statement row to one recorded cash entry. Matching
          is separate from completing a statement reconciliation.
        </p>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Date / ID</th>
                <th>Description</th>
                <th>Amount</th>
                <th>Match</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {data.bankTransactions.map((row) => {
                const match = data.bankMatches.find(
                  (item) => item.transaction_id === row.id,
                );
                return (
                  <tr key={row.id}>
                    <td>
                      {row.posted_on}
                      <small>{row.external_id}</small>
                    </td>
                    <td>{row.description}</td>
                    <td>{dollars(cents(row.amount))}</td>
                    <td>
                      {match ? (
                        <>
                          <strong>Matched</strong>
                          <small>
                            {match.entry_date} · {match.memo}
                          </small>
                        </>
                      ) : (
                        "Unmatched"
                      )}
                    </td>
                    <td>
                      {match ? (
                        <button
                          className="secondary"
                          disabled={busy}
                          aria-label={`Undo match ${row.external_id}`}
                          onClick={async () => {
                            if (
                              window.confirm(
                                `Undo the match for ${row.external_id}? The recorded payment or expense stays in the ledger.`,
                              )
                            )
                              await act(
                                `/api/bank/transactions/${row.id}/unmatch`,
                                { matchId: match.id },
                                "Match removed. The ledger is unchanged.",
                              );
                          }}
                        >
                          Undo match
                        </button>
                      ) : (
                        <button
                          className="secondary"
                          disabled={busy}
                          aria-label={`Review ${row.external_id}`}
                          onClick={() => void review(row.id)}
                        >
                          Review entries
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!data.bankTransactions.length && (
          <p className="empty">Import a bank CSV before reviewing matches.</p>
        )}
      </section>
      {transaction && (
        <section className="card">
          <h2>Review recorded entries</h2>
          <p>
            <strong>
              {transaction.external_id} · {transaction.description} ·{" "}
              {dollars(cents(transaction.amount))}
            </strong>
            <br />
            Statement date: {transaction.posted_on}
          </p>
          <p>
            Entries below have the same amount and direction and are not already
            matched. The closest dates appear first. Check the description and
            supporting document; equal amounts or nearby dates do not prove a
            match.
          </p>
          {entries === null ? (
            <p>Loading recorded entries…</p>
          ) : entries.length === 0 ? (
            <p className="empty">
              No eligible recorded entries have this amount and direction. Check
              the books and record any missing payment or expense before
              matching.
            </p>
          ) : (
            <form
              onSubmit={async (event) => {
                event.preventDefault();
                if (!choice) return;
                if (
                  !window.confirm(
                    `Match ${transaction.external_id} to ${choice.kind.toLowerCase()}: ${choice.memo} (${choice.entry_date}, ${dollars(cents(choice.amount))})?`,
                  )
                )
                  return;
                await act(
                  `/api/bank/transactions/${transaction.id}/match`,
                  { lineId: choice.line_id },
                  "Bank transaction matched. The ledger is unchanged.",
                );
              }}
            >
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Select</th>
                      <th>Recorded date</th>
                      <th>Type / Description</th>
                      <th>Amount</th>
                      <th>Date comparison</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((entry, index) => (
                      <tr key={entry.line_id}>
                        <td>
                          <label className="matching-option">
                            <input
                              type="radio"
                              name="entry"
                              required
                              disabled={busy}
                              checked={lineId === entry.line_id}
                              onChange={() => setLineId(entry.line_id)}
                            />
                            Select entry {index + 1}
                          </label>
                        </td>
                        <td>{entry.entry_date}</td>
                        <td>
                          <strong>{entry.kind}</strong>
                          <small>{entry.memo}</small>
                        </td>
                        <td>{dollars(cents(entry.amount))}</td>
                        <td>
                          {Number(entry.days_apart) === 0
                            ? "Same date"
                            : `${entry.days_apart} day${Number(entry.days_apart) === 1 ? "" : "s"} apart`}
                          <small>
                            {entry.near_date
                              ? "Within seven days"
                              : "Check the date difference"}
                          </small>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <button disabled={busy || !choice}>Confirm match</button>
            </form>
          )}
        </section>
      )}
      <section className="card">
        <h2>Match history</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Bank transaction</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {data.bankMatchEvents.map((event) => (
                <tr key={event.id}>
                  <td>{event.occurred_at.replace("T", " ").slice(0, 19)}</td>
                  <td>
                    {
                      data.bankTransactions.find(
                        (row) => row.id === event.transaction_id,
                      )?.external_id
                    }
                  </td>
                  <td>
                    {event.action === "MATCHED" ? "Matched" : "Match removed"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!data.bankMatchEvents.length && (
          <p className="empty">No matches recorded yet.</p>
        )}
      </section>
    </>
  );
}
