// mermaid-check.mjs - real-browser verification of mermaid rendering.
// Drives the actual chat.html + chat.js + bundled mermaid.min.js in headless
// Microsoft Edge (the same engine family as the Eclipse WebView2 chat view),
// through the public bridge contract (__setMessages), and asserts diagrams
// render to SVG - or degrade VISIBLY - never silently disappear.
// Run: node mermaid-check.mjs   (exit 0 = pass; prints SKIP + exit 0 when
// Edge or puppeteer-core is unavailable, so builds stay green elsewhere)
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, extname } from "node:path";
import { fileURLToPath } from "node:url";

const webDir = join(dirname(fileURLToPath(import.meta.url)), "web");

const MIME = {
  ".html": "text/html", ".js": "text/javascript", ".css": "text/css",
  ".svg": "image/svg+xml", ".woff2": "font/woff2", ".png": "image/png",
};

function findEdge() {
  for (const p of [
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    process.env.EDGE_PATH,
  ]) {
    if (p && existsSync(p)) return p;
  }
  return null;
}

async function importPuppeteer() {
  try { return (await import("puppeteer-core")).default; }
  catch { return null; }
}

let failures = 0;
const check = (name, ok, detail = "") => {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? " - " + detail : ""}`);
  if (!ok) failures++;
};

const FLOWCHART = "```mermaid\nflowchart TD\n  A[Start] --> B{Choice}\n  B -->|yes| C[Do it]\n  B -->|no| D[Skip]\n  C --> E[End]\n  D --> E\n```";

async function main() {
  const edge = findEdge();
  const puppeteer = await importPuppeteer();
  if (!edge || !puppeteer) {
    console.log(`SKIP  mermaid-check (edge: ${edge ? "found" : "missing"}, puppeteer-core: ${puppeteer ? "found" : "missing"})`);
    process.exit(0);
  }

  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://localhost");
      const file = join(webDir, url.pathname === "/" ? "chat.html" : url.pathname.slice(1));
      if (!file.startsWith(webDir)) { res.writeHead(400).end(); return; }
      const body = await readFile(file);
      res.writeHead(200, { "Content-Type": MIME[extname(file)] || "application/octet-stream" });
      res.end(body);
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise(r => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;

  const browser = await puppeteer.launch({
    executablePath: edge,
    headless: "new",
    args: ["--no-sandbox", "--disable-gpu"],
  });
  const page = await browser.newPage();
  const jsErrors = [];
  page.on("pageerror", e => jsErrors.push(String(e)));

  await page.evaluateOnNewDocument(() => {
    window.__reports = [];
    window.__javaReport = m => { window.__reports.push(String(m)); };
  });
  await page.goto(`http://127.0.0.1:${port}/chat.html`, { waitUntil: "load" });
  await page.waitForFunction(
    () => window.__reports && window.__reports.some(m => m.includes("page-ready")),
    { timeout: 10000 });

  // 1) happy path: a flowchart in an assistant message must render to SVG
  await page.evaluate(msgs => window.__setMessages(JSON.stringify(msgs)), [
    { role: "user", id: "", text: "draw me a flowchart", reasoning: "", meta: "" },
    { role: "assistant", id: "msg_m1", text: "Here you go:\n\n" + FLOWCHART, reasoning: "", meta: "test/model" },
  ]);
  await page.waitForFunction(() => {
    const el = document.querySelector(".mermaid");
    return el && (el.querySelector("svg") || el.textContent.trim().length > 0);
  }, { timeout: 15000 });
  const svgOk = await page.evaluate(() => !!document.querySelector(".mermaid svg"));
  check("mermaid diagram renders to SVG in real Edge", svgOk);

  // 1b) the FULL syntax surface the TUI answers use: subgraphs + shape-decorated
  //     nodes (cylinders). The vendored mermaid 0.x bundle silently failed exactly
  //     this class while the simple flowchart passed - which is why this check
  //     exists (U-101). Must never regress to "simple diagrams only".
  const FANCY = "```mermaid\nflowchart TB\n"
      + "    subgraph EXT[\"External runtime\"]\n"
      + "        OC[(opencode service)]\n"
      + "    end\n"
      + "    subgraph ENG[\"Engine\"]\n"
      + "        TASKS[tasks engine]\n"
      + "        FLEET[fleet engine]\n"
      + "    end\n"
      + "    FLEET --> TASKS\n"
      + "    OC --- ENG\n"
      + "```";
  await page.evaluate(msgs => window.__setMessages(JSON.stringify(msgs)), [
    { role: "assistant", id: "msg_m1b", text: "Architecture:\n\n" + FANCY, reasoning: "", meta: "" },
  ]);
  const fancyOk = await page.waitForFunction(() => {
    const m = document.querySelector(".mermaid");
    return m && (m.querySelector("svg") || m.classList.contains("error"));
  }, { timeout: 15000 }).then(() => true).catch(() => false);
  const fancySvg = await page.evaluate(() => {
    const m = document.querySelector(".mermaid");
    return !!m && m.querySelector("svg") !== null;
  });
  check("fancy mermaid surface renders (subgraphs + cylinders, U-101)", fancyOk && fancySvg,
    "svg=" + fancySvg);

  // 2) diagnostics: no MISSING / FAILED markers on the happy path
  const reports = await page.evaluate(() => window.__reports.join("\n"));
  check("mermaid api was found (fallback chain works)", !/api: MISSING/.test(reports));
  check("no mermaid render FAILED report", !/mermaid render FAILED|mermaid run threw/.test(reports),
    reports.split("\n").filter(l => /mermaid/.test(l)).join(" | "));
  check("page reported mermaid initialised", /mermaid initialised/.test(reports));

  // 3) negative path: a broken diagram must degrade VISIBLY, not vanish
  //    (chat.js re-classes the div to .error with the reason + source text)
  await page.evaluate(msgs => window.__setMessages(JSON.stringify(msgs)), [
    { role: "assistant", id: "msg_m2", text: "```mermaid\nthis is :: not <> a diagram\n```", reasoning: "", meta: "" },
  ]);
  await page.waitForFunction(() => {
    const m = document.querySelector(".mermaid");
    const e = document.querySelector(".error");
    return (m && (m.querySelector("svg") || m.textContent.trim().length > 0))
        || (e && e.textContent.trim().length > 0);
  }, { timeout: 15000 });
  const brokenVisible = await page.evaluate(() => {
    const m = document.querySelector(".mermaid");
    const e = document.querySelector(".error");
    const visibleM = !!m && (m.querySelector("svg") !== null || m.textContent.trim().length > 0);
    const visibleE = !!e && e.textContent.trim().length > 0;
    return visibleM || visibleE;
  });
  check("broken diagram stays visible (no silent drop)", brokenVisible);
  const negReports = await page.evaluate(() => window.__reports.join("\n"));
  check("broken diagram was reported", /mermaid render FAILED|mermaid run threw/.test(negReports) ||
    /mermaid diagram rendered/.test(negReports), // v11 may draw its own error SVG - also visible
    negReports.split("\n").filter(l => /mermaid/.test(l)).join(" | "));

  // 4) re-render after __clear + second message still works (lazy re-init path)
  await page.evaluate(() => window.__clear());
  await page.evaluate(msgs => window.__setMessages(JSON.stringify(msgs)), [
    { role: "assistant", id: "msg_m3", text: FLOWCHART, reasoning: "", meta: "" },
  ]);
  await page.waitForFunction(() => !!document.querySelector(".mermaid svg"), { timeout: 15000 });
  check("diagram renders again after clear (re-render)", true);

  check("no unexpected page JS errors", jsErrors.length === 0, jsErrors.join(" | "));

  await browser.close();
  server.close();
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(e => { console.error("mermaid-check ERROR", e); process.exit(1); });
