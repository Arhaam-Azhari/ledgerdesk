export function FirstUse({ owner, busy, open }: {
  owner: boolean;
  busy: boolean;
  open: (page: string) => void;
}) {
  return (
    <section className="card first-use" aria-labelledby="first-use-title">
      <h2 id="first-use-title">Start your books</h2>
      <p>No accounting entries have been posted yet. This workspace keeps one business in USD.</p>
      {owner && <button className="secondary" disabled={busy} onClick={() => open("Business settings")}>Set business details</button>}
      <ol>
        <li>
          <h3>Choose where your books begin</h3>
          <p>For an existing business, bring in reviewed balances and unpaid invoices or bills before recording new activity. A new business with no prior balances can go straight to its first transaction.</p>
          {owner ? (
            <button className="secondary" disabled={busy} onClick={() => open("Opening books")}>Review opening books</button>
          ) : <p>Ask an owner to record any opening balances first.</p>}
        </li>
        <li>
          <h3>Add the people you work with</h3>
          <p>Add customers for invoices and vendors for bills. The sample customer is fictional.</p>
          <div className="first-use-actions">
            <button className="secondary" disabled={busy} onClick={() => open("Customers")}>Add customers</button>
            <button className="secondary" disabled={busy} onClick={() => open("Vendors")}>Add vendors</button>
          </div>
        </li>
        <li>
          <h3>Record and check your first transaction</h3>
          <p>Post an invoice, then record payments as they arrive. Use dated reports to check the results. Save a backup before bringing in real records.</p>
          <div className="first-use-actions">
            <button disabled={busy} onClick={() => open("Invoices")}>Create an invoice</button>
            <button className="secondary" disabled={busy} onClick={() => open("Reports")}>Check reports</button>
          </div>
        </li>
      </ol>
    </section>
  );
}
