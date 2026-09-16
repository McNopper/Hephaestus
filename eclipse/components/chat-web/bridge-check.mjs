// bridge-check.mjs - executes the chat page's Java bridge against a DOM shim.
//
// This is the test that the old string-matching renderer-check could not do:
// it runs chat.html's script and calls the bridge with the EXACT script strings
// Java produces (com.opencode.ide.chat.internal.ChatScripts), then asserts that
// content actually lands in the DOM. The original bug (Java passed a JS object
// literal while the page did JSON.parse(string), so every render threw and
// Browser.execute still returned true) fails loudly here.
//
// Run: node bridge-check.mjs   (exit 0 = pass)
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import vm from "node:vm";

const require = createRequire(import.meta.url);
// WEB_DIR override exists so the suite can be run against a mutated copy of the
// page (used to prove these checks actually fail when the bridge is broken).
const webDir = process.env.WEB_DIR
  || join(dirname(fileURLToPath(import.meta.url)), "web");
let failures = 0;
const check = (name, ok, detail = "") => {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? " - " + detail : ""}`);
  if (!ok) failures++;
};

// ---------------- minimal DOM shim ----------------

const stripTags = (html) => String(html).replace(/<[^>]*>/g, "");
/** Visible text of rendered HTML: tags removed and entities decoded. */
const visibleText = (html) => stripTags(html)
  .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
  .replace(/&quot;/g, "\"").replace(/&#x27;|&apos;/g, "'")
  .replace(/&amp;/g, "&");

class El {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.children = [];
    this.dataset = {};
    this.className = "";
    this.parentElement = null;
    this.scrollTop = 0;
    this.scrollHeight = 0;
    this._html = null;
    this._text = null;
    this.nodeType = 1;
  }
  get classList() {
    const self = this;
    const list = () => self.className.split(/\s+/).filter(Boolean);
    return {
      contains: (c) => list().includes(c),
      add: (c) => { if (!list().includes(c)) self.className = list().concat(c).join(" "); },
      remove: (c) => { self.className = list().filter(x => x !== c).join(" "); },
      toggle: (c, force) => {
        const on = force === undefined ? !list().includes(c) : !!force;
        if (on) { if (!list().includes(c)) self.className = list().concat(c).join(" "); }
        else { self.className = list().filter(x => x !== c).join(" "); }
        return on;
      }
    };
  }
  appendChild(child) {
    // real-DOM semantics: appending an existing child MOVES it - detached
    // from its old parent, never duplicated anywhere
    if (child.parentElement && child.parentElement !== this) {
      const old = child.parentElement.children.indexOf(child);
      if (old >= 0) {
        child.parentElement.children.splice(old, 1);
      }
    }
    const at = this.children.indexOf(child);
    if (at >= 0) {
      this.children.splice(at, 1);
    }
    child.parentElement = this;
    this.children.push(child);
    return child;
  }
  insertBefore(node, ref) {
    // real-DOM semantics: inserting an existing child MOVES it (no duplicates)
    if (node.parentElement && node.parentElement !== this) {
      const old = node.parentElement.children.indexOf(node);
      if (old >= 0) {
        node.parentElement.children.splice(old, 1);
      }
    }
    const at = this.children.indexOf(node);
    if (at >= 0) {
      this.children.splice(at, 1);
    }
    node.parentElement = this;
    const atRef = ref ? this.children.indexOf(ref) : -1;
    if (atRef < 0) this.children.push(node);
    else this.children.splice(atRef, 0, node);
    return node;
  }
  removeChild(child) {
    const at = this.children.indexOf(child);
    if (at >= 0) this.children.splice(at, 1);
    child.parentElement = null;
    return child;
  }
  remove() { if (this.parentElement) this.parentElement.removeChild(this); }
  addEventListener(type, handler) {
    (this._listeners || (this._listeners = {}))[type] = handler;
  }
  set innerHTML(html) {
    this._html = String(html);
    this._text = null;
    this.children = [];
    parseInto(this, this._html);
  }
  // real-DOM fidelity: a container built via appendChild (the block-level
  // streaming structure) serializes its children, like a browser would
  get innerHTML() { return this._html === null ? serializeChildren(this) : this._html; }
  set textContent(text) { this._text = String(text); this._html = null; this.children = []; }
  get textContent() { return textOf(this); }
  replaceWith(node) {
    const parent = this.parentElement;
    if (!parent) return;
    parent.children[parent.children.indexOf(this)] = node;
    node.parentElement = parent;
  }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  querySelectorAll(sel) {
    const parts = String(sel).split(">").map(s => s.trim());
    const target = parts[parts.length - 1];
    const parentSel = parts.length > 1 ? parts[parts.length - 2] : null;
    const out = [];
    walk(this, (el) => {
      if (matches(el, target) && (!parentSel || (el.parentElement && matches(el.parentElement, parentSel)))) {
        out.push(el);
      }
    });
    return out;
  }
}

function walk(el, visit) {
  for (const child of el.children) {
    if (child.nodeType === 1) { visit(child); walk(child, visit); }
  }
}

// minimal innerHTML serialization of an appendChild-built subtree: tags with
// their class and data-* attributes, text nodes escaped - only what the
// assertions below need (they use .includes on real markup)
const escapeText = (text) => String(text)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
function serializeChildren(node) {
  let out = "";
  for (const child of node.children) {
    if (child.nodeType === 3) { out += escapeText(child.textContent); continue; }
    const cls = child.className ? " class=\"" + child.className + "\"" : "";
    const dataAttrs = Object.keys(child.dataset || {})
      .map(k => " data-" + k + "=\"" + child.dataset[k] + "\"").join("");
    const tag = child.tagName.toLowerCase();
    out += "<" + tag + cls + dataAttrs + ">";
    if (!VOID_TAGS.has(child.tagName)) {
      out += serializeChildren(child) + "</" + tag + ">";
    }
  }
  return out;
}

// supports: tag, .class(.class), [attr="value"] (data-* only)
function matches(el, selector) {
  const attr = selector.match(/\[data-([a-zA-Z0-9_-]+)="([^"]*)"\]/);
  let rest = selector.replace(/\[[^\]]*\]/g, "");
  if (attr && el.dataset[attr[1]] !== attr[2]) return false;
  const classes = (rest.match(/\.[A-Za-z0-9_-]+/g) || []).map(c => c.slice(1));
  const tag = rest.replace(/\.[A-Za-z0-9_-]+/g, "").trim();
  if (tag && el.tagName !== tag.toUpperCase()) return false;
  const own = el.className.split(/\s+/).filter(Boolean);
  return classes.every(c => own.includes(c));
}

function textOf(node) {
  if (node.nodeType === 3) return node.textContent;
  let text = node._text !== null && node._text !== undefined ? node._text
    : (node._html ? decodeEntities(stripTags(node._html)) : "");
  for (const child of node.children) text += textOf(child);
  return text;
}

// like a real DOM textContent: markup stripped AND character entities decoded
function decodeEntities(text) {
  return String(text)
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, "\"").replace(/&#x27;|&apos;|&#39;/g, "'")
    .replace(/&amp;/g, "&");
}

// Minimal HTML tree builder: markdown/KaTeX/hljs output is set via innerHTML,
// and features like the per-fence Copy button must find those elements via
// querySelector - exactly as in a real browser (which parses the markup).
const VOID_TAGS = new Set(["BR", "HR", "IMG", "INPUT", "META", "LINK", "SOURCE"]);
function parseInto(parent, html) {
  const stack = [parent];
  const text = String(html);
  const pushText = (host, chunk) => {
    if (chunk) host.appendChild({ nodeType: 3, textContent: decodeEntities(chunk), children: [] });
  };
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf("<", i);
    if (open < 0) { pushText(stack[0], text.slice(i)); break; }
    if (open > i) pushText(stack[0], text.slice(i, open));
    if (text.startsWith("<!--", open)) {
      const end = text.indexOf("-->", open);
      i = end < 0 ? text.length : end + 3;
      continue;
    }
    const close = text.indexOf(">", open);
    if (close < 0) { pushText(stack[0], text.slice(open)); break; }
    let tag = text.slice(open + 1, close).trim();
    if (tag.startsWith("!")) { i = close + 1; continue; } // doctype
    if (tag.startsWith("/")) {
      const name = tag.slice(1).split(/\s+/)[0].toUpperCase();
      // pop up to and including the INNERMOST matching open tag (stack[0] is
      // the innermost); a stray closer with no match is ignored (lenient)
      for (let s = 0; s < stack.length; s++) {
        if (stack[s].tagName === name) { stack.splice(0, s + 1); break; }
      }
      i = close + 1;
      continue;
    }
    const selfClosing = tag.endsWith("/");
    if (selfClosing) tag = tag.slice(0, -1);
    const name = tag.match(/^[a-zA-Z][a-zA-Z0-9:-]*/);
    if (!name) { pushText(stack[0], text.slice(open, close + 1)); i = close + 1; continue; }
    const el = new El(name[0]);
    const classAttr = tag.match(/class\s*=\s*"([^"]*)"|class\s*=\s*'([^']*)'/);
    if (classAttr) el.className = classAttr[1] || classAttr[2] || "";
    stack[0].appendChild(el);
    if (!selfClosing && !VOID_TAGS.has(el.tagName)) stack.unshift(el);
    i = close + 1;
  }
}

// ---------------- load the page script ----------------

const html = readFileSync(join(webDir, "chat.html"), "utf8");
check("chat.html loads the app script", html.includes("chat.js"));
const script = readFileSync(join(webDir, "chat.js"), "utf8");
check("chat.js is the real app script", script.includes("__appendUser") && script.includes("extractMath"));

const chatEl = new El("div");
const bodyEl = new El("body");
const reports = [];
const ctx = {
  console,
  JSON, Math, Date, String, Number, Boolean, Object, Array, RegExp, Error, Promise,
  parseInt, parseFloat,
  setTimeout, clearTimeout,
  markdownit: require(join(webDir, "markdown-it.min.js")),
  document: {
    body: bodyEl,
    getElementById: (id) => (id === "chat" ? chatEl : null),
    createElement: (tag) => new El(tag),
    createTextNode: (text) => ({ nodeType: 3, textContent: String(text), children: [] })
  },
  addEventListener: () => {},
  __javaReport: (msg) => reports.push(String(msg))
};
ctx.katex = require(join(webDir, "katex", "katex.min.js"));
ctx.hljs = require(join(webDir, "hljs", "highlight.min.js"));
// language packs self-register against the hljs instance (as in the browser)
globalThis.hljs = ctx.hljs; // the UMD language pack registers against the global, as in the browser
try {
  const cmake = require(join(webDir, "hljs", "cmake.min.js"));
  if (typeof cmake === "function") ctx.hljs.registerLanguage("cmake", cmake);
} catch (e) {
  console.log("NOTE  cmake language pack not registered in Node: " + e.message);
}
ctx.window = ctx;
ctx.globalThis = ctx;
vm.createContext(ctx);

let loadError = null;
try {
  vm.runInContext(script, ctx, { filename: "chat.html" });
} catch (e) {
  loadError = e;
}
check("page script runs without throwing", loadError === null, loadError ? String(loadError) : "");
check("page reports page-ready", reports.includes("page-ready"));

// Runs a script string exactly as SWT Browser.execute() would.
const exec = (js) => vm.runInContext(js, ctx, { filename: "bridge" });

// ---------------- the Java -> JS contract (ChatScripts output) ----------------

// These literals are what com.opencode.ide.chat.internal.ChatScripts produces;
// ChatScriptsTest asserts the Java side emits exactly this shape.
const r1 = exec('window.__appendUser("{\\"text\\":\\"hello **world**\\"}")');
check("__appendUser(JSON string) returns true", r1 === true);
check("__appendUser renders the prompt text", textOf(chatEl).includes("hello world"),
  JSON.stringify(textOf(chatEl)).slice(0, 80));
check("__appendUser reports to Java", reports.some(r => r.startsWith("user bubble rendered")));

// object-literal form must not silently do nothing (the original bug)
const before = reports.length;
const r2 = exec('window.__appendUser({"text":"object form"})');
check("__appendUser(object) is tolerated", r2 === true && textOf(chatEl).includes("object form"));
check("__appendUser(object) reported a render", reports.length > before);

// streaming: PROGRESSIVE markdown (throttled ~5 renders/s). The first chunk of
// a burst paints immediately (leading edge); chunks inside the throttle window
// coalesce into one trailing render - __flushStream drives that pending tick
// deterministically, exactly like the checks need (no real timers to wait for).
exec('window.__startAssistant("{\\"mid\\":\\"msg_1\\"}")');
exec('window.__appendDelta("{\\"mid\\":\\"msg_1\\",\\"text\\":\\"ack\\"}")');
const streamNode = chatEl.querySelector('.msg.assistant[data-mid="msg_1"]');
check("__startAssistant creates the reply bubble", !!streamNode);
check("__appendDelta renders the first chunk immediately (leading edge)",
  !!streamNode && textOf(streamNode).includes("ack"),
  streamNode ? JSON.stringify(textOf(streamNode)) : "no node");
check("__appendDelta leaves the blinking cursor in place",
  !!streamNode && streamNode.querySelectorAll(".cursor").length === 1,
  "cursors=" + (streamNode ? streamNode.querySelectorAll(".cursor").length : "no-node"));
exec('window.__appendDelta("{\\"mid\\":\\"msg_1\\",\\"text\\":\\"nowledged\\"}")');
check("a chunk inside the throttle window is coalesced (not painted yet)",
  !!streamNode && !textOf(streamNode).includes("acknowledged"),
  streamNode ? JSON.stringify(textOf(streamNode)) : "no node");
const rf = exec('window.__flushStream("msg_1")');
check("__flushStream paints the pending tick", rf === true && textOf(streamNode).includes("acknowledged"));
check("__flushStream keeps the cursor streaming",
  !!streamNode && streamNode.querySelectorAll(".cursor").length === 1);
check("__flushStream with nothing pending is a harmless false",
  exec('window.__flushStream("msg_1")') === false);

// cursor stop: the host calls __stopStream when the send completes/fails/aborts;
// the streamed text must survive, the cursor must go, and it must be idempotent
const rs0 = exec('window.__stopStream("{\\"mid\\":\\"msg_1\\"}")');
check("__stopStream returns true", rs0 === true);
check("__stopStream removes the cursor but keeps the streamed text",
  !!streamNode && streamNode.querySelectorAll(".cursor").length === 0
    && textOf(streamNode).includes("acknowledged"));
exec('window.__stopStream("{\\"mid\\":\\"msg_1\\"}")');
check("__stopStream is idempotent", streamNode.querySelectorAll(".cursor").length === 0);
const rs1 = exec('window.__stopStream("{\\"mid\\":\\"no_such_bubble\\"}")');
check("__stopStream tolerates an unknown mid", rs1 === true);

// progressive rendering: a streamed MARKDOWN table must be formatted DURING
// generation (this is the parity feature - raw pipes were the old behavior),
// on the very first (leading-edge) render, before any finalization
exec('window.__startAssistant("{\\"mid\\":\\"msg_table\\"}")');
exec('window.__appendDelta("{\\"mid\\":\\"msg_table\\",\\"text\\":\\"| a | b |\\\\n|---|---|\\\\n| 1 | 2 |\\"}")');
const tableNode = chatEl.querySelector('.msg.assistant[data-mid="msg_table"]');
check("streamed table renders FORMATTED while streaming (no final render yet)",
  !!tableNode && tableNode.querySelector(".body").innerHTML.includes("<table>"),
  tableNode ? tableNode.querySelector(".body").innerHTML.slice(0, 60) : "no node");
check("streamed table keeps the streaming cursor",
  !!tableNode && tableNode.querySelectorAll(".cursor").length === 1);
// the live-rendered trailing table gets SEALED by the next separator: it must
// be promoted to a committed block exactly once, never duplicated
exec('window.__appendDelta("{\\"mid\\":\\"msg_table\\",\\"text\\":\\"\\\\n\\\\nafter the table\\"}")');
check("the sealed live table is promoted, not duplicated",
  !!tableNode && tableNode.querySelectorAll("table").length === 1
    && !!tableNode.querySelector(".body > .stream-raw")
    && tableNode.querySelector(".body > .stream-raw").textContent.includes("after the table"),
  tableNode ? "tables=" + tableNode.querySelectorAll("table").length : "no node");
check("the promoted table keeps its streaming cursor",
  !!tableNode && tableNode.querySelectorAll(".cursor").length === 1);
exec('window.__stopStream("{\\"mid\\":\\"msg_table\\"}")');
check("__stopStream finalizes the streamed table (still a rendered table)",
  !!tableNode && tableNode.querySelector(".body").innerHTML.includes("<table>"),
  tableNode ? tableNode.querySelector(".body").innerHTML.slice(0, 60) : "no node");
check("__stopStream leaves no cursor on the finalized bubble",
  !!tableNode && tableNode.querySelectorAll(".cursor").length === 0);

// finalize must run the FULL render pipeline, not just plain markdown:
// streamed math -> KaTeX, streamed mermaid fence -> diagram pass. The LIVE
// pass deliberately skips mermaid (incomplete source must not error on every
// throttle tick) - the fence stays highlighted code until finalization.
exec('window.__startAssistant("{\\"mid\\":\\"msg_mix\\"}")');
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_mix", text: "### Result\n\n| k | v |\n|---|---|\n| 1 | $c^2$ |" })) + ")");
const mixNode = chatEl.querySelector('.msg.assistant[data-mid="msg_mix"]');
check("live render resolves the heading during streaming",
  !!mixNode && mixNode.querySelector(".body").innerHTML.includes("<h3>Result</h3>"));
check("live render resolves the table during streaming",
  !!mixNode && mixNode.querySelector(".body").innerHTML.includes("<table>"));
check("live render resolves inline math via KaTeX during streaming",
  !!mixNode && !!mixNode.querySelector(".katex"),
  mixNode ? JSON.stringify(textOf(mixNode)).slice(0, 60) : "no node");
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_mix", text: "\n\n```mermaid\ngraph TD; A-->B;\n```" })) + ")");
exec('window.__flushStream("msg_mix")');
check("live render keeps the mermaid fence as code (no diagram pass mid-stream)",
  !!mixNode && mixNode.querySelectorAll("code.language-mermaid").length === 1
    && mixNode.querySelectorAll(".mermaid").length === 0);
exec('window.__stopStream("{\\"mid\\":\\"msg_mix\\"}")');
check("finalized stream renders the heading",
  !!mixNode && mixNode.querySelector(".body").innerHTML.includes("<h3>Result</h3>"));
check("finalized stream renders the table",
  !!mixNode && mixNode.querySelector(".body").innerHTML.includes("<table>"));
check("finalized stream renders inline math via KaTeX",
  !!mixNode && !!mixNode.querySelector(".katex"),
  mixNode ? JSON.stringify(textOf(mixNode)).slice(0, 60) : "no node");
check("finalized stream runs the mermaid pass (fence replaced, no copy button on it)",
  !!mixNode && mixNode.querySelectorAll("code.language-mermaid").length === 0
    && mixNode.querySelectorAll(".copy-btn").length === 0);

// ---------------- block-level progressive rendering (PO refinement) ----------
// A block that completed mid-stream (sealed by a later separator) renders as
// markdown IMMEDIATELY as its own element and is never touched again; only
// the trailing in-progress block stays raw under the cursor. Structural
// commits paint on the spot (cost scales with the new blocks, not the whole
// message); raw-tail repaints ride the throttle.
exec('window.__startAssistant("{\\"mid\\":\\"msg_blk\\"}")');
const thinkNode = chatEl.querySelector('.msg.assistant[data-mid="msg_blk"]');
check("stream start shows the thinking indicator",
  !!thinkNode && !!thinkNode.querySelector(".thinking")
    && textOf(thinkNode).includes("thinking"),
  thinkNode ? "" : "no node");
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_blk", text: "sealed paragraph\n\npartial sen" })) + ")");
const blkNode = chatEl.querySelector('.msg.assistant[data-mid="msg_blk"]');
check("sealed paragraph renders as markdown during streaming",
  !!blkNode && blkNode.querySelector(".body").innerHTML.includes("<p>sealed paragraph</p>"),
  blkNode ? blkNode.querySelector(".body").innerHTML.slice(0, 60) : "no node");
const sealedDiv = blkNode ? blkNode.querySelector(".stream-block") : null;
check("the sealed block lives in its own element",
  !!sealedDiv && sealedDiv.innerHTML.includes("<p>sealed paragraph</p>"));
const rawTail = blkNode ? blkNode.querySelector(".body > .stream-raw") : null;
check("the trailing in-progress block stays raw",
  !!rawTail && rawTail.textContent.includes("partial sen"));
check("the cursor rides at the raw tail",
  !!blkNode && !!blkNode.querySelector(".cursor")
    && blkNode.querySelector(".cursor").parentElement === rawTail);
check("the thinking indicator is cleared by the first content chunk",
  !!blkNode && blkNode.querySelector(".thinking") === null);
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_blk", text: "tence\n\nsecond sealed" })) + ")");
const sealedDivs = blkNode.querySelectorAll(".stream-block");
check("newly sealed blocks append after the first (same element, never re-rendered)",
  sealedDivs.length === 2 && sealedDivs[0] === sealedDiv
    && sealedDivs[1].innerHTML.includes("<p>partial sentence</p>"),
  "blocks=" + sealedDivs.length);
check("the new in-progress tail moved into the raw element",
  !!blkNode && blkNode.querySelector(".body > .stream-raw").textContent.includes("second sealed"));
exec('window.__stopStream("{\\"mid\\":\\"msg_blk\\"}")');
check("stopStream finalizes the whole streamed message",
  !!blkNode && blkNode.querySelector(".body").innerHTML.includes("<p>partial sentence</p>")
    && blkNode.querySelectorAll(".cursor").length === 0);

// an OPEN fence is still incomplete - raw until the closing fence arrives,
// then it renders immediately as highlighted code
exec('window.__startAssistant("{\\"mid\\":\\"msg_fence\\"}")');
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_fence", text: "```c\nint partial" })) + ")");
const fenceNode = chatEl.querySelector('.msg.assistant[data-mid="msg_fence"]');
check("an open code fence stays raw (no <pre> yet)",
  !!fenceNode && fenceNode.querySelectorAll("pre").length === 0
    && !!fenceNode.querySelector(".body > .stream-raw"));
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_fence", text: "\n```" })) + ")");
check("a closed fence renders immediately as highlighted code",
  !!fenceNode && fenceNode.querySelectorAll("pre > code.language-c").length === 1
    && fenceNode.querySelectorAll(".body > .stream-raw").length === 0,
  fenceNode ? fenceNode.querySelector(".body").innerHTML.slice(0, 60) : "no node");
check("the cursor keeps streaming after the fence closed",
  !!fenceNode && fenceNode.querySelectorAll(".cursor").length === 1);
exec('window.__stopStream("{\\"mid\\":\\"msg_fence\\"}")');

// thinking/reasoning surfaced DURING generation, not only after finalization
exec('window.__startAssistant("{\\"mid\\":\\"msg_r\\"}")');
exec("window.__appendReasoningDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_r", text: "considering " })) + ")");
const rNode = chatEl.querySelector('.msg.assistant[data-mid="msg_r"]');
check("reasoning streams into the collapsible details during generation",
  !!rNode && !!rNode.querySelector("details.reasoning")
    && textOf(rNode).includes("considering"),
  rNode ? "" : "no node");
check("the streaming details are labelled thinking",
  !!rNode && rNode.querySelector("details.reasoning > summary").textContent === "thinking");
check("the stream cursor stays while only reasoning arrived",
  !!rNode && rNode.querySelectorAll(".cursor").length === 1);
exec("window.__appendReasoningDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_r", text: "options" })) + ")");
check("reasoning repaints ride the same throttle (coalesced)",
  !!rNode && !textOf(rNode).includes("considering options"));
exec('window.__flushStream("msg_r")');
check("a flushed tick paints the accumulated thinking",
  !!rNode && textOf(rNode).includes("considering options"));
exec("window.__appendDelta(" + JSON.stringify(JSON.stringify({ mid: "msg_r", text: "the answer" })) + ")");
exec('window.__stopStream("{\\"mid\\":\\"msg_r\\"}")');
check("stopStream finalizes the text and upgrades reasoning to markdown",
  !!rNode && rNode.querySelector(".body").innerHTML.includes("<p>the answer</p>")
    && rNode.querySelector("details.reasoning > .reasoning-body").innerHTML.includes("<p>considering options</p>"),
  rNode ? rNode.querySelector(".body").innerHTML.slice(0, 60) : "no node");

// final authoritative render (markdown + meta)
const finalScript = 'window.__setAssistantText("{\\"mid\\":\\"msg_1\\",\\"text\\":\\"# Done\\\\n\\\\n`code` and $x^2$\\",'
  + '\\"reasoning\\":\\"\\",\\"meta\\":\\"anthropic/claude\\"}")';
const r3 = exec(finalScript);
check("__setAssistantText returns true", r3 === true);
check("__setAssistantText renders markdown", !!streamNode && streamNode.querySelector(".body").innerHTML.includes("<h1>Done</h1>"));
check("__setAssistantText shows the model meta", !!streamNode && textOf(streamNode).includes("anthropic/claude"));
check("__setAssistantText reports to Java", reports.some(r => r.startsWith("assistant bubble rendered")));

// C--001: an EMPTY authoritative reply must never wipe text that already
// streamed (live 2026-09-15: a 0-char final render blanked a streamed answer)
exec('window.__startAssistant("{\\"mid\\":\\"msg_empty\\"}")');
exec('window.__appendDelta("{\\"mid\\":\\"msg_empty\\",\\"text\\":\\"# Streamed answer\\\\n\\\\nkept alive\\"}")');
const emptyNode = chatEl.querySelector('.msg.assistant[data-mid="msg_empty"]');
exec('window.__setAssistantText("{\\"mid\\":\\"msg_empty\\",\\"text\\":\\"\\",\\"reasoning\\":\\"\\",\\"meta\\":\\"x/y\\"}")');
check("empty final reply keeps the streamed text",
  !!emptyNode && emptyNode.querySelector(".body").innerHTML.includes("<h1>Streamed answer</h1>"),
  emptyNode ? emptyNode.querySelector(".body").innerHTML.slice(0, 60) : "no node");
check("empty final reply was reported, not silent",
  reports.some(r => r.startsWith("empty final reply - kept streamed text")));

// stream-done guard: a delta still in flight when the authoritative render
// lands must not re-open the closed bubble (the old single-late-chunk bug)
exec('window.__appendDelta("{\\"mid\\":\\"msg_1\\",\\"text\\":\\"LATE\\"}")');
check("delta after the authoritative render is dropped (stream-done guard)",
  !!streamNode && !textOf(streamNode).includes("LATE")
    && streamNode.querySelectorAll(".cursor").length === 0);

// history load (resume)
const rows = JSON.stringify([
  { role: "user", id: "", text: "prior question", reasoning: "", meta: "" },
  { role: "assistant", id: "msg_0", text: "prior **answer**", reasoning: "", meta: "openai/gpt" }
]);
const r4 = exec("window.__setMessages(" + JSON.stringify(rows) + ")");
check("__setMessages returns true", r4 === true);
check("__setMessages renders history", textOf(chatEl).includes("prior question") && textOf(chatEl).includes("prior answer"));
check("__setMessages replaced the transcript", !textOf(chatEl).includes("hello world"));

// notice + theme (plain string args)
exec('window.__setNotice("Connected.")');
check("__setNotice renders", textOf(chatEl).includes("Connected."));
exec('window.__setTheme("dark")');
check("__setTheme applies the dark class", bodyEl.className.includes("dark"));
exec('window.__setTheme("light")');
check("__setTheme switches back to light", bodyEl.className.includes("light") && !bodyEl.className.includes("dark"));

// clear
exec("window.__clear()");
check("__clear empties the transcript", textOf(chatEl) === "");

// ---------------- tool parts (compact tool-call lines) ----------------
// ChatEntry.parts tool parts arrive (via ChatScripts) as a "tools" array of
// {name, state} on each history row / final assistant render.
const toolErrBefore = reports.filter(r => r.startsWith("JS ERROR")).length;
const toolRows = JSON.stringify([
  { role: "user", id: "", text: "build it", reasoning: "", meta: "", tools: [] },
  { role: "assistant", id: "msg_t", text: "done", reasoning: "", meta: "",
    tools: [
      { name: "read", state: "running" },
      { name: "bash", state: "completed" },
      { name: "write", state: "error" },
      { name: "<script>alert(1)</script>", state: "weird" }
    ] }
]);
const rt = exec("window.__setMessages(" + JSON.stringify(toolRows) + ")");
check("__setMessages with tool parts returns true", rt === true);
const toolNode = chatEl.querySelector('.msg.assistant[data-mid="msg_t"]');
check("running tool renders its line", !!toolNode && textOf(toolNode).includes("tool: read — running"),
  toolNode ? JSON.stringify(textOf(toolNode)) : "no node");
check("completed tool renders its line with a check",
  !!toolNode && textOf(toolNode).includes("tool: bash — completed ✓"));
check("error tool renders its line", !!toolNode && textOf(toolNode).includes("tool: write — error"));
check("running line carries the running class",
  !!toolNode && toolNode.querySelectorAll(".tool-line.tool-running").length === 1);
check("completed line carries the completed class (dimmed via CSS)",
  !!toolNode && toolNode.querySelectorAll(".tool-line.tool-completed").length === 1);
check("error line carries the error class",
  !!toolNode && toolNode.querySelectorAll(".tool-line.tool-error").length === 1);
check("unknown state still renders with a fallback class",
  !!toolNode && toolNode.querySelectorAll(".tool-line.tool-unknown").length === 1
    && textOf(toolNode).includes("weird"));
check("hostile tool name cannot inject markup",
  !!toolNode && toolNode.querySelectorAll("script").length === 0
    && textOf(toolNode).includes("tool: <script>alert(1)</script>"));
check("tool lines sit above the message body",
  !!toolNode && (() => {
    const bubble = toolNode.querySelector(".bubble");
    const body = toolNode.querySelector(".body");
    const kids = bubble ? bubble.children : [];
    const firstTool = kids.findIndex(c => c.classList && c.classList.contains("tool-line"));
    return firstTool >= 0 && kids.indexOf(body) > firstTool;
  })());
check("__javaReport still fires (history + tool-line reports)",
  reports.some(r => r.startsWith("history rendered")) && reports.some(r => r.includes("tool line(s) rendered")));
check("no JS errors while rendering tool parts",
  reports.filter(r => r.startsWith("JS ERROR")).length === toolErrBefore);

// final assistant render carrying tools (the sendMessage reply shape)
exec("window.__clear()");
const toolFinal = JSON.stringify(
  { mid: "m_t2", text: "hi", reasoning: "", meta: "", tools: [{ name: "grep", state: "completed" }] });
exec("window.__setAssistantText(" + JSON.stringify(toolFinal) + ")");
const t2 = chatEl.querySelector('.msg.assistant[data-mid="m_t2"]');
check("__setAssistantText renders tool lines too",
  !!t2 && textOf(t2).includes("tool: grep — completed"));
exec("window.__setAssistantText(" + JSON.stringify(toolFinal) + ")");
check("re-render does not duplicate tool lines",
  !!t2 && t2.querySelectorAll(".tool-line").length === 1);

// ---------------- math (extract BEFORE markdown) ----------------
// Real model output captured from a live opencode server, plus the shapes that
// the previous "markdown first, KaTeX auto-render after" pipeline destroyed.
const mathCase = (name, markdown, expectations) => {
  exec("window.__clear()");
  exec("window.__setAssistantText(" + JSON.stringify(JSON.stringify(
    { mid: "m_math", text: markdown, reasoning: "", meta: "" })) + ")");
  const node = chatEl.querySelector('.msg.assistant[data-mid="m_math"]');
  const html = node ? node.querySelector(".body").innerHTML : "";
  expectations(name, html);
};

mathCase("real model output ($$ on one line)",
  "Quadratic formula:\n$$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}$$\n\nThe roots $x_1$ and $x_2$ satisfy $ax^2 + bx + c = 0$.",
  (name, html) => {
    check(name + ": display math rendered by KaTeX", html.includes("katex-display"));
    check(name + ": inline math rendered by KaTeX", html.includes("class=\"katex\""));
    check(name + ": no raw $$ left in the output", !html.includes("$$"));
  });

mathCase("multi-line $$ block", "$$\nx = \\frac{-b}{2a}\n$$\n", (name, html) => {
  check(name + ": rendered (used to be raw $$ + <br>)", html.includes("katex-display"));
  check(name + ": no stray <br> inside the formula", !/\$\$/.test(html));
});

mathCase("\\( \\) and \\[ \\] delimiters", "inline \\(x^2\\) and display \\[y = 1\\]", (name, html) => {
  check(name + ": both rendered (markdown used to eat the backslashes)",
    (html.match(/class="katex/g) || []).length >= 2);
  check(name + ": no leftover parenthesis delimiters", !html.includes("\\(") && !html.includes("(x^2)"));
});

mathCase("LaTeX line break \\\\ inside math", "$$a \\\\ b$$", (name, html) => {
  check(name + ": survived markdown (\\\\ used to collapse to \\)", html.includes("katex"));
});

mathCase("math must not touch code blocks", "```c\nint cost = $5; /* $x$ */\n```", (name, html) => {
  check(name + ": no KaTeX inside code", !html.includes("katex"));
  // hljs tokenises, so compare the text content rather than the raw markup
  check(name + ": code content intact", stripTags(html).includes("$5") && stripTags(html).includes("$x$"),
    JSON.stringify(stripTags(html)));
});

mathCase("currency is not math", "It costs $5 and then $10 more.", (name, html) => {
  check(name + ": left as plain text", !html.includes("katex") && html.includes("$5"));
});

mathCase("broken TeX degrades visibly, never silently", "$$\\frac{1}{$$", (name, html) => {
  check(name + ": something is rendered", html.length > 0);
});

// ---------------- syntax highlighting ----------------
const codeCase = (name, markdown, expectations) => {
  exec("window.__clear()");
  exec("window.__setAssistantText(" + JSON.stringify(JSON.stringify(
    { mid: "m_code", text: markdown, reasoning: "", meta: "" })) + ")");
  const node = chatEl.querySelector('.msg.assistant[data-mid="m_code"]');
  expectations(name, node ? node.querySelector(".body").innerHTML : "");
};

codeCase("C code block", "```c\n#include <stdio.h>\nint main(void) { return 0; }\n```", (name, html) => {
  check(name + ": highlighted", html.includes("hljs-"));
  check(name + ": tagged as C", html.includes("language-c"));
  check(name + ": preprocessor token recognised", html.includes("hljs-meta"));
  check(name + ": angle brackets escaped", html.includes("&lt;stdio.h&gt;"));
});

codeCase("C++ code block", "```cpp\ntemplate<typename T>\nclass Foo { public: T bar() const noexcept; };\n```",
  (name, html) => {
    check(name + ": highlighted", html.includes("hljs-keyword"));
    check(name + ": tagged as C++", html.includes("language-cpp"));
  });

codeCase("CMake code block", "```cmake\nadd_executable(app main.c)\n```", (name, html) => {
  check(name + ": cmake language registered", html.includes("language-cmake"));
});

codeCase("unknown language does not break", "```klingon\nqapla'\n```", (name, html) => {
  check(name + ": still rendered as code", html.includes("<code") && html.includes("qapla"));
});

codeCase("untagged fence still styled", "```\nplain text\n```", (name, html) => {
  check(name + ": hljs class present", html.includes("hljs"));
});

codeCase("mermaid fence is NOT highlighted", "```mermaid\ngraph TD; A-->B;\n```", (name, html) => {
  check(name + ": kept as language-mermaid for the diagram pass", html.includes("language-mermaid"));
  check(name + ": not tokenised by hljs", !html.includes("hljs-keyword"));
});

codeCase("code cannot inject HTML", "```html\n<script>alert(1)</script>\n```", (name, html) => {
  // hljs emits &lt;<span class="hljs-name">script</span>&gt; - what matters is that
  // no executable tag survives and the text is still readable
  check(name + ": no executable tag in the output", !/<script/i.test(html));
  check(name + ": shown as escaped text", visibleText(html).includes("<script>alert(1)</script>"),
    JSON.stringify(visibleText(html)));
});

// ---------------- copy-code (per-fence Copy button) ----------------
exec("window.__clear()");
exec("window.__setAssistantText(" + JSON.stringify(JSON.stringify(
  { mid: "m_copy", text: "```c\nint add(int a, int b);\n```\n\n```mermaid\ngraph TD; A-->B;\n```",
    reasoning: "", meta: "" })) + ")");
const copyNode = chatEl.querySelector('.msg.assistant[data-mid="m_copy"]');
const pres = copyNode ? copyNode.querySelectorAll("pre") : [];
const copyBtn = pres.length > 0 ? pres[0].querySelector(".copy-btn") : null;
check("code fence has a Copy button", !!copyBtn);
check("button lives inside its fence (positioned top-right via CSS)",
  !!copyBtn && copyBtn.parentElement === pres[0]);
check("the mermaid fence was replaced by the diagram pass (no button for it)",
  pres.length === 1 && copyNode.querySelectorAll(".copy-btn").length === 1);
check("button is labelled Copy", !!copyBtn && copyBtn.textContent === "Copy");

// the shim has neither a clipboard API nor execCommand: the copy path must
// degrade to a report instead of throwing or lying (length only - no content)
const copyErrBefore = reports.filter(r => r.startsWith("JS ERROR")).length;
const rcNoApi = ctx.__copyCode(copyBtn);
check("__copyCode returns true without any clipboard API", rcNoApi === true);
check("unavailable copy is reported to Java (length only, never the code text)",
  reports.some(r => r.startsWith("copy unavailable") && r.endsWith(" chars)"))
    && !reports.some(r => r.includes("int add(int a, int b)")),
  reports.filter(r => r.startsWith("copy unavailable")).slice(-1)[0] || "");

// execCommand fallback: intercept it and capture the textarea's value
let execCopied = null;
ctx.document.execCommand = (cmd) => {
  if (cmd !== "copy") return false;
  const ta = bodyEl.children.find(c => c.tagName === "TEXTAREA");
  execCopied = ta ? ta.value : null;
  return true;
};
const rc = ctx.__copyCode(copyBtn);
check("__copyCode returns true via the execCommand fallback", rc === true);
check("RAW code text was copied, not the highlighted markup (trailing \\n included)",
  execCopied === "int add(int a, int b);\n", JSON.stringify(execCopied));
check("copy is reported to Java", reports.some(r => r.startsWith("code copied")));
check("button briefly confirms 'Copied'",
  copyBtn.textContent === "Copied" && copyBtn.classList.contains("copied"));

// the click listener wired by addCopyButtons drives the same hook
execCopied = null;
if (copyBtn._listeners && typeof copyBtn._listeners.click === "function") {
  copyBtn._listeners.click();
}
check("clicking the button copies too (listener wired)",
  execCopied === "int add(int a, int b);\n");
check("no JS errors on any copy path",
  reports.filter(r => r.startsWith("JS ERROR")).length === copyErrBefore);

// with a clipboard API present it is preferred, and the confirmation lands
// asynchronously (after the writeText promise settles)
const apiCopied = [];
ctx.navigator = { clipboard: { writeText: (t) => { apiCopied.push(t); return ctx.Promise.resolve(); } } };
ctx.__copyCode(copyBtn);
check("clipboard API is preferred over execCommand",
  apiCopied.length === 1 && apiCopied[0] === "int add(int a, int b);\n");
await Promise.resolve();
check("Copied confirmation shows after the clipboard promise settles",
  copyBtn.textContent === "Copied");

// ---------------- external links ----------------
exec("window.__clear()");
const opened = [];
ctx.__javaOpenExternal = (url) => opened.push(url);
let prevented = false;
const anchor = { tagName: "A", parentElement: null, getAttribute: () => "https://opencode.ai/docs" };
const handled = ctx.__linkClick({ target: anchor, preventDefault: () => { prevented = true; } });
check("link click is intercepted", handled === true);
check("link click is not followed in the view", prevented === true);
check("link is handed to Eclipse for the external browser",
  opened.length === 1 && opened[0] === "https://opencode.ai/docs");

const anchorHash = { tagName: "A", parentElement: null, getAttribute: () => "#section" };
const handledHash = ctx.__linkClick({ target: anchorHash, preventDefault: () => {} });
check("in-page anchors are left alone", handledHash === false && opened.length === 1);

const notALink = { tagName: "SPAN", parentElement: null, getAttribute: () => null };
check("clicks on non-links are ignored",
  ctx.__linkClick({ target: notALink, preventDefault: () => {} }) === false);

// ---------------- failures must be loud ----------------
const errBefore = reports.length;
const bad = exec('window.__appendUser("not json at all")');
check("malformed payload returns false", bad === false);
check("malformed payload is reported as a JS ERROR",
  reports.slice(errBefore).some(r => r.startsWith("JS ERROR in __appendUser")),
  reports.slice(errBefore).join(" | "));

process.exit(failures === 0 ? 0 : 1);
