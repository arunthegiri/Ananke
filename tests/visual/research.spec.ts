import { expect, stubLinkedSec, test } from "./fixtures";

/**
 * /research + Settings → Data connectors (Connectors roadmap §1–§2, issue #10).
 * Behavioural, not pixel baselines: the SEC payload is a recorded fixture, and what
 * matters is the state machine (not linked → pending, linked → tables) and that the
 * tool calls carry the right arguments.
 */

test("not linked: research shows a pending state that deep-links to Settings", async ({ page }) => {
  await page.goto("/research?symbol=AAPL");
  await expect(page.getByText(/Link SEC EDGAR to read filings here/)).toBeVisible();
  const link = page.getByRole("link", { name: "Open Settings → Data connectors" });
  await expect(link).toHaveAttribute("href", "/settings?section=connectors");
});

test("linked: filings table with ET acceptance times, document links and source line", async ({
  page,
}) => {
  const { calls } = await stubLinkedSec(page);
  await page.goto("/research?symbol=AAPL");

  // 2026-11-09T21:30:40Z is 16:30 ET (EST, UTC-5).
  await expect(page.getByText("2026-11-09 16:30")).toBeVisible();
  await expect(page.getByRole("link", { name: "ef20085077_8k.htm" })).toHaveAttribute(
    "href",
    "https://www.sec.gov/Archives/edgar/data/320193/000114036126042798/ef20085077_8k.htm",
  );
  await expect(page.getByTestId("research-source")).toContainText("Apple Inc. · CIK 0000320193");
  await expect(page.getByTestId("research-source")).toContainText(
    "Securities and Exchange Commission",
  );
  expect(calls[0]).toEqual({ tool: "sec_edgar_filings", args: { ticker: "AAPL", limit: 50 } });
});

test("tabs and ticker are URL state", async ({ page }) => {
  const { calls } = await stubLinkedSec(page);
  await page.goto("/research?symbol=AAPL");
  // Wait for the hydrated table before clicking: a click on the SSR markup has no handler.
  await expect(page.getByText("2026-11-09 16:30")).toBeVisible();
  await page.getByRole("tab", { name: "Insider trades" }).click();
  await expect(page).toHaveURL(/tab=insiders/);
  await expect(
    page.getByText("Newstead Jennifer · SVP, GC and Government Affairs").first(),
  ).toBeVisible();
  await expect(page.getByText("SELL").first()).toBeVisible();
  expect(calls.map((c) => c.tool)).toContain("sec_edgar_insider_trades");

  await page.getByTestId("research-ticker").fill("msft");
  await page.getByTestId("research-go").click();
  await expect(page).toHaveURL(/symbol=MSFT/);
});

test("settings: SEC EDGAR lists its tools and sends the identity on Link & test", async ({
  page,
}) => {
  let sent: unknown = null;
  await page.route(/\/api\/connectors\/sec_edgar\/credentials/, (route) => {
    sent = route.request().postDataJSON();
    return route.fulfill({
      status: 400,
      contentType: "application/json",
      headers: { "Access-Control-Allow-Origin": "*" },
      body: JSON.stringify({
        message: "The SEC asks for a contact email in the identity.",
        status: 400,
      }),
    });
  });
  await page.goto("/settings?section=connectors");

  const row = page.getByTestId("connector-sec_edgar");
  await expect(row).toContainText("Free · public data");
  await expect(row).toContainText("Not linked");
  await expect(row).toContainText("sec_edgar_insider_trades");
  await expect(row).toContainText("8 / second");

  await page.getByTestId("connector-field-sec_edgar-identity").fill("just a name");
  await page.getByTestId("connector-save-sec_edgar").click();
  await expect(page.getByTestId("connector-message-sec_edgar")).toContainText("contact email");
  expect(sent).toEqual({ values: { identity: "just a name" } });
});

test("all sources: runs any linked tool from its schema and tables the result", async ({
  page,
}) => {
  const { calls } = await stubLinkedSec(page);
  await page.goto("/research?tab=sources&src=sec_edgar&tool=sec_edgar_filings");
  await expect(page.getByTestId("source-explorer")).toBeVisible();
  await expect(page.getByTestId("source-tool-sec_edgar_insider_trades")).toBeVisible();

  // ticker is required: Run stays disabled until it's filled.
  await expect(page.getByTestId("source-run")).toBeDisabled();
  await page.getByTestId("source-arg-ticker").fill("AAPL");
  await page.getByTestId("source-arg-limit").fill("3");
  await page.getByTestId("source-run").click();

  // Columns derived from the payload's first array (filings); URLs become links.
  await expect(page.getByText("filings: 3 rows")).toBeVisible();
  await expect(page.getByText("0001140361-26-042798")).toBeVisible();
  expect(calls.at(-1)).toEqual({ tool: "sec_edgar_filings", args: { ticker: "AAPL", limit: 3 } });

  await page.getByRole("button", { name: "Raw JSON" }).click();
  await expect(page.getByTestId("source-json")).toContainText('"cik": "0000320193"');
});
