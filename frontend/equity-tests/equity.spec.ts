import { test, expect } from "@playwright/test";

test("record owner transfers, retry an uncertain refresh and check dated equity", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function state() {
    const response = await request.get("/api/state", { headers: auth });
    expect(response.ok()).toBe(true);
    return response.json();
  }
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page
    .getByRole("button", { name: "Owner transfers", exact: true })
    .click();
  await expect(
    page.getByText("No owner transfers recorded yet."),
  ).toBeVisible();
  async function fill(
    kind: string,
    date: string,
    memo: string,
    amount: string,
  ) {
    await page.getByLabel("Transfer type").selectOption(kind);
    await page.getByLabel("Transfer date").fill(date);
    await page.getByLabel("Transfer memo").fill(memo);
    await page.getByLabel("Transfer amount (USD)").fill(amount);
  }
  async function record() {
    await page
      .getByRole("button", { name: "Record transfer", exact: true })
      .click();
    await expect(page.getByRole("status")).toHaveText(
      "Owner transfer recorded.",
    );
    await expect(page.getByLabel("Transfer memo")).toHaveValue("");
  }
  await fill(
    "CONTRIBUTION",
    "2026-10-01",
    "Personal savings for business setup",
    "1000.00",
  );
  await record();
  await fill("DRAWING", "2026-10-03", "Owner's personal withdrawal", "200.00");
  await record();
  const metrics = page.locator("section.metrics");
  await expect(metrics).toContainText("$1,000.00");
  await expect(metrics).toContainText("$200.00");
  await expect(metrics).toContainText("$800.00");
  await expect(page.getByRole("row")).toHaveCount(3);
  let data = await state();
  expect(data.equityTransactions).toHaveLength(2);
  expect(data.ledger).toHaveLength(4);
  await page.screenshot({
    path: "equity-results/owner-transfers.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await expect(
    page.getByRole("button", { name: "Record transfer", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "equity-results/mobile-owner-transfers.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });

  // Posting can succeed before the following refresh loses its connection.
  let failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await fill("CONTRIBUTION", "2026-10-04", "Extra setup funds", "50.00");
  await page
    .getByRole("button", { name: "Record transfer", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).equityTransactions).toHaveLength(3);
  await expect(page.getByLabel("Transfer memo")).toHaveValue(
    "Extra setup funds",
  );
  await record();
  expect((await state()).equityTransactions).toHaveLength(3);
  await page.unroute("**/api/state");
  await fill("DRAWING", "2026-11-01", "November personal withdrawal", "100.00");
  await record();
  await expect(metrics).toContainText("$750.00");
  await fill("CONTRIBUTION", "2026-10-05", "Invalid precision", "1.001");
  await page
    .getByRole("button", { name: "Record transfer", exact: true })
    .click();
  expect(
    await page
      .getByLabel("Transfer amount (USD)")
      .evaluate((input: HTMLInputElement) => input.validity.patternMismatch),
  ).toBe(true);
  data = await state();
  expect(data.equityTransactions).toHaveLength(4);
  expect(data.ledger).toHaveLength(8);
  expect(
    data.audit.filter((a: { action: string }) => a.action.startsWith("OWNER_")),
  ).toHaveLength(4);

  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("$0.00");
  await page
    .getByRole("button", { name: "Balance sheet", exact: true })
    .click();
  await expect(
    page.getByRole("row").filter({ hasText: "Total assets" }),
  ).toContainText("$850.00");
  await expect(
    page.getByRole("row").filter({ hasText: "Total equity" }),
  ).toContainText("$850.00");
  await expect(
    page.getByRole("row").filter({ hasText: "Accumulated earnings" }),
  ).toContainText("$0.00");
  await page
    .getByRole("button", { name: "Owner transfers", exact: true })
    .click();
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(page.getByRole("row")).toHaveCount(5);
  await expect(page.locator("section.metrics")).toContainText("$750.00");
  expect((await state()).ledger).toEqual(data.ledger);
});
