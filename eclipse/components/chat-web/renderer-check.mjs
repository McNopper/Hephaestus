// renderer-check.mjs - component test for the bundled chat web assets.
// Verifies (without Eclipse): vendor libs load in Node, markdown/KaTeX actually
// render sample content, and chat.html exposes the Java-bridge JS API.
// Run: node renderer-check.mjs   (exit 0 = pass)
import { readFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const webDir = join(dirname(fileURLToPath(import.meta.url)), "web");
let failures = 0;
const check = (name, ok, detail = "") => {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? " - " + detail : ""}`);
  if (!ok) failures++;
};

// --- presence ---
for (const f of ["chat.html", "chat.js", "markdown-it.min.js", "mermaid.min.js",
  "katex/katex.min.js", "katex/katex.min.css",
  "hljs/highlight.min.js", "hljs/github.min.css", "hljs/github-dark.min.css", "hljs/cmake.min.js"]) {
  check(`exists ${f}`, existsSync(join(webDir, f)));
}
const fontsOk = existsSync(join(webDir, "katex", "fonts"));
check("katex fonts dir exists", fontsOk);
if (fontsOk) {
  const woff2 = readFileSync(join(webDir, "katex", "katex.min.css"), "utf8")
    .match(/fonts\/KaTeX_[A-Za-z]+-Regular\.woff2/g) || [];
  const allPresent = woff2.every(rel => existsSync(join(webDir, "katex", rel)));
  check("katex css-referenced woff2 fonts present", allPresent, `${woff2.length} referenced`);
}

// --- markdown-it renders (UMD loads in Node) ---
try {
  const md = require(join(webDir, "markdown-it.min.js"))({ html: false, linkify: true });
  const html = md.render("# Title\n\n**bold** and `code`\n\n| a | b |\n|---|---|\n| 1 | 2 |");
  check("markdown-it renders heading", html.includes("<h1>Title</h1>"));
  check("markdown-it renders bold", html.includes("<strong>bold</strong>"));
  check("markdown-it renders table", html.includes("<table>"));
  const fenced = md.render("```mermaid\ngraph TD; A-->B;\n```");
  check("mermaid fence becomes language class", fenced.includes('class="language-mermaid"'));
} catch (e) {
  check("markdown-it loads", false, String(e));
}

// --- KaTeX renders math (UMD loads in Node) ---
try {
  const katex = require(join(webDir, "katex", "katex.min.js"));
  const inline = katex.renderToString("c = \\pm\\sqrt{a^2 + b^2}");
  check("katex inline math", inline.includes("katex") && inline.includes("sqrt"));
  const display = katex.renderToString("\\int_0^1 x^2 dx", { displayMode: true });
  check("katex display math", display.includes("katex-display"));
} catch (e) {
  check("katex loads", false, String(e));
}

// --- highlight.js renders C/C++ (the project's languages) ---
try {
  const hljs = require(join(webDir, "hljs", "highlight.min.js"));
  check("hljs supports C", !!hljs.getLanguage("c"));
  check("hljs supports C++", !!hljs.getLanguage("cpp") && !!hljs.getLanguage("c++"));
  check("hljs supports makefile/bash", !!hljs.getLanguage("makefile") && !!hljs.getLanguage("bash"));
  const c = hljs.highlight('#include <stdio.h>\nint main(void){return 0;}', { language: "c" }).value;
  check("hljs tokenises C", c.includes("hljs-meta") && c.includes("hljs-type"));
  check("hljs escapes angle brackets", c.includes("&lt;stdio.h&gt;"));
  const cpp = hljs.highlight("template<typename T> class A {};", { language: "cpp" }).value;
  check("hljs tokenises C++", cpp.includes("hljs-keyword"));
} catch (e) {
  check("hljs loads", false, String(e));
}

// --- chat.html / chat.js wiring ---
try {
  const html = readFileSync(join(webDir, "chat.html"), "utf8");
  const js = readFileSync(join(webDir, "chat.js"), "utf8");
  for (const fn of ["__setTheme", "__clear", "__setMessages", "__appendUser",
    "__startAssistant", "__appendDelta", "__appendReasoningDelta", "__flushStream",
    "__setAssistantText", "__linkClick", "__copyCode"]) {
    check(`chat.js exposes ${fn}`, js.includes(`window.${fn} =`));
  }
  check("chat.html loads chat.js", html.includes("chat.js"));
  check("chat.html loads markdown-it", html.includes("markdown-it.min.js"));
  check("chat.html loads katex css+js", html.includes("katex.min.css") && html.includes("katex.min.js"));
  check("chat.html loads highlight.js + themes",
    html.includes("hljs/highlight.min.js") && html.includes("hljs/github.min.css")
    && html.includes("hljs/github-dark.min.css"));
  check("chat.html loads mermaid", html.includes("mermaid.min.js"));
  check("only #chat scrolls (no double scrollbar)", /html, body \{[^}]*overflow: hidden/.test(html));
  check("math is extracted before markdown", js.includes("extractMath") && js.includes("restoreMath"));
  check("mermaid strict security", js.includes('securityLevel: "strict"'));
  check("markdown html disabled (XSS)", js.includes("html: false"));
  check("bridge payloads tolerate string and object", js.includes("function payload("));
  check("bridge calls are guarded and report errors", js.includes("function guard("));
  // tool parts (compact tool-call lines on assistant messages)
  check("tool parts render as compact lines", js.includes("renderToolLines") && js.includes("tool-line"));
  // cursor-stop finalization: an orphaned stream bubble must not keep raw
  // markdown (pipes) or a blinking cursor once the host stops its stream
  check("stopStream finalizes streamed markdown",
    /__stopStream[\s\S]*?renderMarkdown/.test(js) && js.includes('"stream-done"'));
  // block-level progressive streaming: completed blocks render as markdown in
  // their own elements, only the trailing block stays raw under the cursor
  check("streaming renders block-level markdown",
    js.includes("splitMarkdownBlocks") && js.includes("stream-block"));
  check("streaming throttles whole repaints",
    js.includes("STREAM_RENDER_MS") && js.includes("setTimeout"));
  check("thinking is surfaced during streaming",
    js.includes('"thinking"') && js.includes("__appendReasoningDelta"));
  check("chat.html styles the raw streaming tail", html.includes(".stream-raw"));
  check("chat.html styles the thinking indicator", html.includes(".thinking"));  check("tool lines are built without innerHTML (XSS)", /renderToolLines[\s\S]*?insertBefore/.test(js)
    && !/line\.innerHTML/.test(js));
  check("chat.html styles tool lines per state",
    html.includes(".tool-line.tool-running") && html.includes(".tool-line.tool-completed")
      && html.includes(".tool-line.tool-error"));
  // copy-code (per-fence Copy button, clipboard + execCommand fallback)
  check("copy buttons are added to fences", js.includes("addCopyButtons") && js.includes("copy-btn"));
  check("copy prefers the clipboard API with a fallback",
    js.includes("navigator.clipboard") && js.includes("execCommand"));
  check("chat.html styles the copy button", html.includes(".copy-btn") && html.includes("position: absolute"));
} catch (e) {
  check("chat.html/chat.js readable", false, String(e));
}

process.exit(failures === 0 ? 0 : 1);
