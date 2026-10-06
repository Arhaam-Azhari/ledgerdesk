import { test, expect } from "@playwright/test";

test("review opening books, recover a lost import response and read unchanged history across roles", async ({ page, request }) => {
  test.setTimeout(60000);
  const auth = { Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}` };
  async function post(path: string, data: object, key: string) {
    const csrf = await (await request.get("/api/csrf")).json();
    const response = await request.post(path, { headers: { ...auth, [csrf.headerName]: csrf.token, "Idempotency-Key": "opening-browser-" + key }, data });
    expect(response.ok(), await response.text()).toBe(true);
    return (await response.json()).id as string;
  }
  const history = async () => (await (await request.get("/api/opening-books", { headers: auth })).json());
  const vendor = await post("/api/vendors", { name: "Harbor Supplies", email: "accounts@harbor.example" }, "vendor");
  await post("/api/accounts", { username: "opening-clerk", password: "clerk-local-only", role: "BOOKKEEPER" }, "clerk");
  async function login(username: string, password: string) {
    await page.getByLabel("Username", { exact: true }).fill(username);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Open workspace" }).click();
    await page.getByRole("button", { name: "Opening books", exact: true }).click();
  }
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto("/"); await login("demo", "demo-local-only");
  const load = page.getByRole("button", { name: "Load opening history", exact: true });
  await load.click(); await expect(page.locator(".opening-history")).toContainText("No opening-books imports recorded");
  await page.getByLabel("Prior books end on", { exact: true }).fill("2039-12-31");
  await page.getByLabel("Opening review note", { exact: true }).fill("Agreed to prior trial balance and unpaid documents");
  await page.getByLabel("1000 debit", { exact: true }).fill("1000.25");
  await page.getByLabel("1100 debit", { exact: true }).fill("100.10");
  await page.getByLabel("2000 credit", { exact: true }).fill("40.04");
  await page.getByLabel("3000 credit", { exact: true }).fill("1000.25");
  await page.getByLabel("3300 credit", { exact: true }).fill("60.06");
  await page.getByRole("button", { name: "Add unpaid invoice", exact: true }).click();
  await page.getByRole("combobox", { name: "Invoice 1 customer", exact: true }).selectOption("demo-customer");
  await page.getByLabel("Invoice 1 reference", { exact: true }).fill("OLD-INV-7");
  await page.getByLabel("Invoice 1 description", { exact: true }).fill("Prior design work");
  await page.getByLabel("Invoice 1 unpaid amount", { exact: true }).fill("99.10");
  await page.getByLabel("Invoice 1 issued on", { exact: true }).fill("2039-12-01");
  await page.getByLabel("Invoice 1 due on", { exact: true }).fill("2040-01-15");
  await page.getByRole("button", { name: "Add unpaid bill", exact: true }).click();
  await page.getByRole("combobox", { name: "Bill 1 vendor", exact: true }).selectOption(vendor);
  await page.getByLabel("Bill 1 reference", { exact: true }).fill("OLD-BILL-9");
  await page.getByLabel("Bill 1 description", { exact: true }).fill("Prior supplies");
  await page.getByLabel("Bill 1 unpaid amount", { exact: true }).fill("40.04");
  await page.getByLabel("Bill 1 issued on", { exact: true }).fill("2039-12-02");
  await page.getByLabel("Bill 1 due on", { exact: true }).fill("2040-01-20");
  const preview = page.getByRole("button", { name: "Preview opening books", exact: true });
  const confirm = page.getByRole("button", { name: "Import opening books", exact: true });
  await preview.click(); await expect(page.locator(".opening-preview")).toContainText("Opening preview needs attention"); await expect(confirm).toBeDisabled();
  await page.getByLabel("Invoice 1 unpaid amount", { exact: true }).fill("100.10"); await expect(page.locator(".opening-preview")).toHaveCount(0);
  await preview.click(); await expect(confirm).toBeEnabled(); await expect(page.locator(".opening-preview")).toContainText("Debits $1,100.35");
  await page.route("**/api/opening-books/preview", route => route.fulfill({ status: 503, contentType: "application/json", body: "{}" }));
  await preview.click(); await expect(page.getByRole("alert")).toContainText("could not be completed"); await expect(confirm).toHaveCount(0);
  await page.unroute("**/api/opening-books/preview"); await preview.click(); await expect(confirm).toBeEnabled();
  await page.screenshot({ path: "opening-books-results/opening-books-preview.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "opening-books-results/mobile-opening-books-preview.png", fullPage: true });
  page.once("dialog", dialog => dialog.dismiss()); await confirm.click(); expect((await history()).openingBooks).toHaveLength(0);
  const keys: string[] = []; page.on("request", r => { if (r.method() === "POST" && new URL(r.url()).pathname === "/api/opening-books") keys.push(r.headers()["idempotency-key"]); });
  page.on("dialog", dialog => dialog.accept());
  await page.route("**/api/opening-books", async route => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch(); expect(response.ok()).toBe(true);
    await route.fulfill({ status: 503, contentType: "application/json", body: "{}" });
  });
  await confirm.click(); await expect(page.getByRole("alert")).toContainText("could not be completed");
  await expect(confirm).toBeEnabled(); const original = await history(); expect(original.openingBooks).toHaveLength(1);
  await page.unroute("**/api/opening-books"); await confirm.click(); await expect(page.getByRole("status")).toContainText("Opening books imported");
  expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]); expect((await history()).openingBooks).toHaveLength(1);
  await expect(preview).toHaveCount(0); await load.click();
  const saved = page.locator(".opening-history-record"); await expect(saved).toContainText("OLD-INV-7"); await expect(saved).toContainText("paid $0.00");
  await saved.getByText("Original opening review", { exact: true }).click(); await saved.getByText("Opening evidence references", { exact: true }).click();
  await expect(saved).toContainText(original.openingBooks[0].id);
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.screenshot({ path: "opening-books-results/opening-books-history.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "opening-books-results/mobile-opening-books-history.png", fullPage: true });
  await post(`/api/invoices/${original.receivables[0].invoice_id}/payments`, { paidOn: "2040-01-05", amount: "100.10" }, "collection");
  await post(`/api/bills/${original.payables[0].bill_id}/payments`, { paidOn: "2040-01-05", amount: "40.04" }, "payment");
  await page.getByRole("button", { name: "Reload workspace", exact: true }).click(); await expect(saved).toHaveCount(0); await load.click();
  await expect(saved).toContainText("paid $100.10 · remaining $0.00"); await expect(saved).toContainText("paid $40.04 · remaining $0.00");
  expect((await history()).openingBooks[0].snapshot).toBe(original.openingBooks[0].snapshot);
  await page.route("**/api/opening-books", route => route.fulfill({ status: 503, contentType: "application/json", body: "{}" }));
  await load.click(); await expect(saved).toHaveCount(0); await expect(page.getByRole("alert")).toContainText("could not be completed"); await page.unroute("**/api/opening-books");
  for (const [username, password] of [["reviewer", "reviewer-local-only"], ["opening-clerk", "clerk-local-only"]]) {
    await page.getByRole("button", { name: "Lock workspace", exact: true }).click(); await login(username, password);
    await expect(preview).toHaveCount(0); await expect(confirm).toHaveCount(0); await load.click(); await expect(saved).toContainText("OLD-INV-7");
    await saved.getByText("Original opening review", { exact: true }).click(); await expect(saved).toContainText("Debits $1,100.35");
    const roleAuth = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}` };
    const csrf = await (await request.get("/api/csrf")).json();
    for (const path of ["/api/opening-books", "/api/opening-books/preview"]) expect((await request.post(path, { headers: { ...roleAuth, [csrf.headerName]: csrf.token }, data: {} })).status()).toBe(403);
  }
});
