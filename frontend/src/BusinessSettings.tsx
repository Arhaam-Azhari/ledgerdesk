import { useState } from "react";

export function BusinessSettings({ name, version, busy, save }: {
  name: string;
  version: string;
  busy: boolean;
  save: (name: string, version: string) => Promise<boolean>;
}) {
  const [draft, setDraft] = useState(name);
  // Keep the revision from when this form opened, so another owner's edit is not overwritten.
  const [revision] = useState(version);
  return (
    <section className="card">
      <h2>Business details</h2>
      <p>This installation keeps one business in USD, with calendar-year earnings closing.</p>
      <form onSubmit={async (event) => {
        event.preventDefault();
        await save(draft, revision);
      }}>
        <fieldset className="owner-fields" disabled={busy}>
          <label>Business name
            <input required maxLength={120} value={draft} onChange={(event) => setDraft(event.target.value)} />
          </label>
          <button>Save business details</button>
        </fieldset>
      </form>
      <p>The name appears in the workspace and newly downloaded invoice PDFs, including copies of older invoices. Saving it does not change balances, dates or invoice numbers.</p>
    </section>
  );
}
