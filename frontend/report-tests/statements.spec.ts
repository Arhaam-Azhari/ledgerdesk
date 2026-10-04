import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("customer statements retain historical balances, download PDFs and clear stale results", async ({ page, request }) => {
  test.setTimeout(60000);
  const auth = { Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}` };
  async function post(path: string, data: object, key: string) {
    const csrf = await (await request.get("/api/csrf")).json();
    const response = await request.post(path, { headers: { ...auth, [csrf.headerName]: csrf.token, "Idempotency-Key": "statement-" + key }, data });
    expect(response.ok(), await response.text()).toBe(true);
    return (await response.json()).id as string;
  }
  const customer = await post("/api/customers", { name: "Cedar Design Partners", email: "accounts@cedar.example" }, "customer");
  const formulaCustomer = await post("/api/customers", { name: "=2+2", email: "formula@statement.example" }, "formula");
  const invoice = (amount: string, date: string, key: string) => post("/api/invoices", { customerId: customer, description: `Design work ${key}`, issuedOn: date, dueOn: date, amount }, key);
  const payment = (id: string, amount: string, date: string, key: string) => post(`/api/invoices/${id}/payments`, { paidOn: date, amount }, key);
  const old = await invoice("300.30", "2030-09-30", "old");
  await payment(old, "100.10", "2030-09-30", "opening-payment");
  const current = await invoice("200.20", "2030-10-01", "current");
  const oldPayment = await payment(old, "50.05", "2030-10-02", "old-payment");
  await payment(current, "25.25", "2030-10-31", "current-payment");
  const reversed = await invoice("40", "2030-10-10", "reversed");
  await post(`/api/invoices/${reversed}/void`, { date: "2030-10-15" }, "void");
  await payment(old, "150.15", "2030-11-01", "future-old-payment");
  await payment(current, "174.95", "2030-11-01", "future-current-payment");
  const before = await (await request.get("/api/state", { headers: auth })).json();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByRole("button", { name: "Customer statements", exact: true }).click();
  await page.getByRole("combobox", { name: "Statement customer", exact: true }).selectOption(customer);
  await page.getByLabel("Statement start", { exact: true }).fill("2030-10-01");
  await page.getByLabel("Statement end", { exact: true }).fill("2030-10-31");
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  const result = page.locator(".statement-results");
  await expect(result).toContainText("Cedar Design Partners");
  for (const [label, value] of [["Opening amount owed", "$200.20"], ["Invoice charges", "$240.20"], ["Payments received", "$75.30"], ["Invoice reversals", "$40.00"], ["Closing amount owed", "$325.10"]])
    await expect(result.getByRole("row").filter({ hasText: label })).toContainText(value);
  const movements = result.locator(".statement-table tbody tr");
  await expect(movements).toHaveCount(5);
  await movements.nth(1).getByText("Ledger references", { exact: true }).click();
  await expect(movements.nth(1)).toContainText(oldPayment);
  await expect(movements.nth(1)).toContainText(old);
  await expect(movements.nth(1)).toContainText("$350.35");
  await page.screenshot({ path: "report-results/customer-statement.png", fullPage: true });
  async function exported() {
    const downloaded = page.waitForEvent("download");
    await page.getByRole("button", { name: "Export statement CSV", exact: true }).click();
    const file = await downloaded;
    expect(file.suggestedFilename()).toContain(`ledgerdesk-customer-statement-${await page.getByRole("combobox", { name: "Statement customer", exact: true }).inputValue()}`);
    return readFile((await file.path())!, "utf8");
  }
  let pdfDownloads = 0;
  page.on("download", (file) => { if (file.suggestedFilename().endsWith(".pdf")) pdfDownloads++; });
  const pdfButton = page.getByRole("button", { name: "Download statement PDF", exact: true });
  async function downloadedPdf(save = false) {
    const downloading = page.waitForEvent("download");
    const responding = page.waitForResponse((response) => response.url().includes("/statement/pdf?"));
    await pdfButton.click();
    const response = await responding;
    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"]).toBe("application/pdf");
    expect(response.headers()["cache-control"]).toBe("no-store");
    expect(response.headers()["x-content-type-options"]).toBe("nosniff");
    const url = new URL(response.url());
    expect(url.pathname).toContain(`/customers/${customer}/statement/pdf`);
    expect(url.searchParams.get("startsOn")).toBe(await page.getByLabel("Statement start", { exact: true }).inputValue());
    expect(url.searchParams.get("endsOn")).toBe(await page.getByLabel("Statement end", { exact: true }).inputValue());
    const file = await downloading;
    expect(file.suggestedFilename()).toBe(`ledgerdesk-customer-statement-${url.searchParams.get("startsOn")}-${url.searchParams.get("endsOn")}.pdf`);
    expect((await readFile((await file.path())!)).subarray(0, 5).toString()).toBe("%PDF-");
    if (save) await file.saveAs("report-results/customer-statement-download.pdf");
  }
  const pdfRoute = "**/api/reports/customers/*/statement/pdf?*";
  let release!: () => void;
  const heldRequest = new Promise<void>((resolve) => { release = resolve; });
  await page.route(pdfRoute, async (route) => { await heldRequest; await route.continue(); });
  const firstDownload = downloadedPdf(true);
  await expect(pdfButton).toBeDisabled();
  await expect(page.getByLabel("Statement start", { exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Export statement CSV", exact: true })).toBeDisabled();
  release();
  await firstDownload;
  await page.unroute(pdfRoute);
  for (const [status, contentType, body, message] of [
    [503, "application/json", "{}", "Could not download the statement PDF"],
    [200, "text/html", "<html>Error page</html>", "response was not a PDF"],
    [200, "application/pdf", "not a document", "response was not a PDF"],
  ] as const) {
    await page.route(pdfRoute, (route) => route.fulfill({ status, contentType, body }));
    await pdfButton.click();
    await expect(page.getByRole("alert")).toContainText(message);
    await expect(pdfButton).toBeEnabled();
    await expect(result).toContainText("$325.10");
    expect(pdfDownloads).toBe(1);
    await page.unroute(pdfRoute);
  }
  await downloadedPdf();
  await expect(page.getByRole("alert")).toHaveCount(0);
  expect(pdfDownloads).toBe(2);
  const csv = await exported();
  expect(csv).toContain('"Customer","Cedar Design Partners"');
  expect(csv).toContain('"Period start","2030-10-01"');
  expect(csv).toContain('"Period end","2030-10-31"');
  expect(csv).toContain('"Closing amount owed","325.10"');
  expect(csv).toContain('"0.00","50.05","350.35"');
  expect(csv).toContain(oldPayment);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const scroll = result.locator(".statement-movements");
  expect(await scroll.evaluate((element) => { element.scrollLeft = element.scrollWidth; return element.scrollLeft; })).toBeGreaterThan(0);
  await page.screenshot({ path: "report-results/mobile-customer-statement.png", fullPage: true });
  await page.getByLabel("Statement start").fill("2030-10-16");
  await page.getByLabel("Statement end").fill("2030-10-30");
  await expect(result).toHaveCount(0);
  await expect(pdfButton).toHaveCount(0);
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  await expect(result).toContainText("No customer activity in this period");
  await expect(result.getByRole("row").filter({ hasText: "Closing amount owed" })).toContainText("$350.35");
  await downloadedPdf();
  await page.getByLabel("Statement end").fill("2030-10-01");
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("valid statement period");
  await page.getByLabel("Statement end").fill("2030-10-30");
  let failed = false;
  await page.route("**/api/reports/customers/*/statement?*", async (route) => {
    if (!failed) { failed = true; await route.fulfill({ status: 503, contentType: "application/json", body: "{}" }); }
    else await route.continue();
  });
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("could not be completed");
  await expect(page.getByRole("button", { name: "Export statement CSV" })).toHaveCount(0);
  await expect(pdfButton).toHaveCount(0);
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  await expect(result).toContainText("$350.35");
  await page.unroute("**/api/reports/customers/*/statement?*");
  await page.getByRole("button", { name: "Reload workspace", exact: true }).click();
  await expect(result).toHaveCount(0);
  await expect(pdfButton).toHaveCount(0);
  await page.getByRole("combobox", { name: "Statement customer", exact: true }).selectOption(formulaCustomer);
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  expect(await exported()).toContain('"Customer","\'=2+2"');
  await page.getByRole("combobox", { name: "Statement customer", exact: true }).selectOption(customer);
  await expect(result).toHaveCount(0);
  await expect(pdfButton).toHaveCount(0);
  await page.getByRole("button", { name: "Run statement", exact: true }).click();
  await page.getByRole("button", { name: "Single period reports", exact: true }).click();
  await expect(page.getByRole("button", { name: "Export statement CSV" })).toHaveCount(0);
  await expect(pdfButton).toHaveCount(0);
  const after = await (await request.get("/api/state", { headers: auth })).json();
  expect(after.ledger).toEqual(before.ledger);
  expect(after.trialBalance).toEqual(before.trialBalance);
  expect(after.audit).toEqual(before.audit);
});
