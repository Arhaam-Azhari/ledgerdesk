import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("preview and import a statement, skip overlapping rows, reject conflicts, and preserve the ledger", async ({
  page,
  request,
}) => {
  await expect
    .poll(
      async () =>
        request
          .get("/api/csrf")
          .then((r) => r.status())
          .catch(() => 0),
      { timeout: 20000 },
    )
    .toBe(200);
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Bank imports", exact: true }).click();
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  const before = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download example CSV" }).click();
  const example = await downloadPromise;
  expect(example.suggestedFilename()).toBe("bank-statement.csv");
  await example.saveAs("test-results/bank-statement.csv");
  expect(await readFile("test-results/bank-statement.csv", "utf8")).toBe(
    await readFile("../docs/examples/bank-statement.csv", "utf8"),
  );
  await page
    .getByLabel("Bank CSV file")
    .setInputFiles("../docs/examples/bank-statement.csv");
  await page.getByLabel("Import label").fill("September–October statement");
  await page
    .getByRole("button", { name: "Preview import", exact: true })
    .click();
  const preview = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", { name: "Import preview", exact: true }),
    });
  await expect(preview).toContainText("3 new transactions");
  await expect(
    preview.getByRole("row").filter({ hasText: "BANK-2026-002" }),
  ).toContainText("-$200.00");
  const unsaved = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  expect(unsaved.bankTransactions).toEqual(before.bankTransactions);
  await page.screenshot({
    path: "test-results/bank-preview.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Confirm import" }).click();
  await expect(page.getByRole("status")).toContainText("Bank CSV imported");
  const transactions = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", {
        name: "Statement transactions",
        exact: true,
      }),
    });
  await expect(transactions.locator("tbody tr")).toHaveCount(3);
  const after = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  expect(after.ledger).toEqual(before.ledger);
  expect(after.trialBalance).toEqual(before.trialBalance);
  await page.screenshot({
    path: "test-results/bank-imports.png",
    fullPage: true,
  });

  const header = "transaction_id,date,description,amount\n";
  await page
    .getByLabel("Bank CSV file")
    .setInputFiles({
      name: "overlap.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(
        header +
          "BANK-2026-003,2026-10-03,Cloudline software,-50\nBANK-2026-004,2026-10-04,New bank charge,-25\n",
      ),
    });
  await page
    .getByRole("button", { name: "Preview import", exact: true })
    .click();
  await expect(preview).toContainText(
    "1 new transaction · 1 already imported",
  );
  await expect(
    preview.getByRole("row").filter({ hasText: "BANK-2026-003" }),
  ).toContainText("Skip duplicate");
  await page.getByRole("button", { name: "Confirm import" }).click();
  await expect(page.getByRole("status")).toContainText("Bank CSV imported");
  await expect(transactions.locator("tbody tr")).toHaveCount(4);
  const unchanged = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  expect(unchanged.ledger).toEqual(before.ledger);
  await page
    .getByLabel("Bank CSV file")
    .setInputFiles({
      name: "changed.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(
        header + "BANK-2026-003,2026-10-03,Cloudline software,-60\n",
      ),
    });
  await page
    .getByRole("button", { name: "Preview import", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("different details");
  await expect(
    page.getByRole("button", { name: "Confirm import" }),
  ).toHaveCount(0);
  await expect(transactions.locator("tbody tr")).toHaveCount(4);
  await page
    .getByLabel("Bank CSV file")
    .setInputFiles({
      name: "bad-encoding.csv",
      mimeType: "text/csv",
      buffer: Buffer.from([0xff, 0xfe, 0xff]),
    });
  await expect(page.getByRole("alert")).toContainText("valid UTF-8");
  await expect(
    page.getByRole("button", { name: "Preview import", exact: true }),
  ).toBeDisabled();
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByLabel("Bank CSV file")
    .setInputFiles("../docs/examples/bank-statement.csv");
  await page
    .getByRole("button", { name: "Preview import", exact: true })
    .click();
  await expect(preview).toContainText(
    "0 new transactions · 3 already imported",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/mobile-bank-imports.png",
    fullPage: true,
  });
});
