"use strict";

function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[c]));
}

// Syntax highlighting for fenced code blocks. The user works on C/C++, so those
// matter most; the bundled highlight.js build covers c, cpp, makefile, bash,
// python, java, json, xml, sql, ... and cmake is registered from its own file.
// Returning markup that starts with "<pre" tells markdown-it to use it verbatim.
function highlightFence(code, lang) {
  const language = String(lang || "").toLowerCase().trim();
  if (language === "mermaid") {
    return ""; // rendered as a diagram later - keep markdown-it's default markup
  }
  if (window.hljs && language && window.hljs.getLanguage(language)) {
    try {
      const html = window.hljs.highlight(code, { language: language, ignoreIllegals: true }).value;
      return "<pre><code class=\"hljs language-" + escapeHtml(language) + "\">" + html + "</code></pre>";
    } catch (e) {
      report("highlight failed (" + language + "): " + (e && e.message ? e.message : String(e)));
    }
  }
  // unknown/untagged language: no token colours, but keep the code styling
  return "<pre><code class=\"hljs" + (language ? " language-" + escapeHtml(language) : "")
    + "\">" + escapeHtml(code) + "</code></pre>";
}

const md = window.markdownit({
  html: false, linkify: true, breaks: true, typographer: false,
  highlight: highlightFence
});
let mermaidReady = false;
function ensureMermaid() {
  if (mermaidReady) return;
  mermaidReady = true;
  try {
    mermaidApi().initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: document.body.classList.contains("dark") ? "dark" : "default"
    });
    report("mermaid initialised");
  } catch (e) {
    // never silent: a missing/broken mermaid used to just drop the diagram
    report("mermaid init FAILED: " + (e && e.message ? e.message : String(e)));
  }
}

/** The bundled mermaid is an esbuild IIFE; it exposes itself on globalThis. */
function mermaidApi() {
  return window.mermaid
    || (typeof globalThis !== "undefined" && globalThis.mermaid)
    || (typeof globalThis !== "undefined" && globalThis.__esbuild_esm_mermaid
        && globalThis.__esbuild_esm_mermaid.default)
    || null;
}

// ---- math ----------------------------------------------------------------
// Math is extracted BEFORE markdown and re-inserted as KaTeX HTML afterwards.
// Rendering math after markdown (KaTeX auto-render walking the DOM) is broken
// for real model output, verified against a live server:
//   * "$$\n x = ... \n$$"  -> breaks:true puts <br> inside, splitting the text
//                             nodes, so the delimiters never match -> raw "$$"
//   * "\(x^2\)" / "\[y\]"  -> markdown eats the backslash escapes -> "(x^2)"
//   * "$$a \\ b$$"         -> markdown collapses "\\" -> LaTeX line break lost
// Extracting first also keeps _, *, ` inside formulas away from markdown.
const MATH_OPEN = "\uE000";   // private-use markers: markdown passes them through
const MATH_CLOSE = "\uE001";

function atLineStart(text, i) {
  return i === 0 || text[i - 1] === "\n";
}

// ``` or ~~~ fenced block -> index just past the closing fence
function fenceEnd(text, i) {
  const marker = text[i];
  let run = 0;
  while (text[i + run] === marker) run++;
  if (run < 3) return -1;
  const fence = marker.repeat(run);
  let scan = text.indexOf("\n", i);
  if (scan < 0) return text.length;
  while (scan < text.length) {
    const lineStart = scan + 1;
    const lineEnd = text.indexOf("\n", lineStart);
    const line = text.slice(lineStart, lineEnd < 0 ? text.length : lineEnd);
    if (line.trim().startsWith(fence)) return lineEnd < 0 ? text.length : lineEnd + 1;
    if (lineEnd < 0) break;
    scan = lineEnd;
  }
  return text.length;
}

// `code` span -> index just past the closing backtick run
function inlineCodeEnd(text, i) {
  let run = 0;
  while (text[i + run] === "`") run++;
  const closing = "`".repeat(run);
  const end = text.indexOf(closing, i + run);
  return end < 0 ? -1 : end + run;
}

// closing "$" of an inline span, or -1 (skips currency: "$5 and $10")
function inlineDollarEnd(text, i) {
  const next = text[i + 1];
  if (next === undefined || /\s/.test(next)) return -1;
  for (let j = i + 1; j < text.length; j++) {
    const ch = text[j];
    if (ch === "\\") { j++; continue; }
    if (ch === "\n" && text[j + 1] === "\n") return -1; // don't cross paragraphs
    if (ch === "$") {
      if (/\s/.test(text[j - 1])) continue;      // "... $" is not a closer
      if (/\d/.test(text[j + 1] || "")) return -1; // "$10" -> currency, not math
      return j;
    }
  }
  return -1;
}

/** Replaces math spans with markers. @return {source, spans} */
function extractMath(text) {
  const spans = [];
  let out = "";
  let i = 0;
  // Strip stray markers from the input so model text can never forge one.
  text = String(text == null ? "" : text).split(MATH_OPEN).join("").split(MATH_CLOSE).join("");
  const mark = (tex, display) => {
    spans.push({ tex: tex, display: display });
    return MATH_OPEN + (spans.length - 1) + MATH_CLOSE;
  };
  while (i < text.length) {
    const ch = text[i];
    if ((ch === "`" || ch === "~") && atLineStart(text, i)) {
      const end = fenceEnd(text, i);
      if (end > 0) { out += text.slice(i, end); i = end; continue; }
    }
    if (ch === "`") {
      const end = inlineCodeEnd(text, i);
      if (end > 0) { out += text.slice(i, end); i = end; continue; }
    }
    if (ch === "\\" && (text[i + 1] === "[" || text[i + 1] === "(")) {
      const display = text[i + 1] === "[";
      const close = display ? "\\]" : "\\)";
      const end = text.indexOf(close, i + 2);
      if (end > 0) { out += mark(text.slice(i + 2, end), display); i = end + 2; continue; }
    }
    if (ch === "$") {
      if (text[i + 1] === "$") {
        const end = text.indexOf("$$", i + 2);
        if (end > 0) { out += mark(text.slice(i + 2, end), true); i = end + 2; continue; }
      } else {
        const end = inlineDollarEnd(text, i);
        if (end > 0) { out += mark(text.slice(i + 1, end), false); i = end + 1; continue; }
      }
    }
    out += ch;
    i++;
  }
  return { source: out, spans: spans };
}

/**
 * Puts the KaTeX-rendered math back into the markdown HTML. The alternation
 * matches whole tags first, so a marker that ended up inside an attribute
 * value (markdown-it copies alt/title text verbatim) is left alone instead of
 * having quote-bearing KaTeX HTML spliced into the attribute.
 */
function restoreMath(html, spans) {
  if (spans.length === 0) return html;
  const pattern = new RegExp("<[^>]*>|" + MATH_OPEN + "(\\d+)" + MATH_CLOSE, "g");
  return html.replace(pattern, (match, index) => {
    if (match.charAt(0) === "<") return match; // a tag: never rewrite attributes
    const span = spans[Number(index)];
    if (!span) return match;
    try {
      return window.katex.renderToString(span.tex, {
        displayMode: span.display,
        throwOnError: false,
        strict: false
      });
    } catch (e) {
      report("KaTeX failed: " + (e && e.message ? e.message : String(e)));
      const raw = span.display ? "$$" + span.tex + "$$" : "$" + span.tex + "$";
      return "<code class=\"error\">" + escapeHtml(raw) + "</code>";
    }
  });
}

function renderMarkdown(el, text) {
  const extracted = extractMath(text || "");
  el.innerHTML = restoreMath(md.render(extracted.source), extracted.spans);
  // mermaid diagrams
  const mermaidBlocks = el.querySelectorAll("pre > code.language-mermaid");
  if (mermaidBlocks.length > 0) {
    ensureMermaid();
    const mermaid = mermaidApi();
    report("mermaid blocks found: " + mermaidBlocks.length + ", api: " + (mermaid ? "yes" : "MISSING"));
    mermaidBlocks.forEach(code => {
      const div = document.createElement("div");
      div.className = "mermaid";
      div.textContent = code.textContent;
      code.parentElement.replaceWith(div);
      if (!mermaid) {
        // visible instead of an empty gap, and reported to the Eclipse log
        div.className = "error";
        div.textContent = "mermaid unavailable - diagram source:\n" + div.textContent;
        return;
      }
      try {
        const result = mermaid.run({ nodes: [div] });
        if (result && typeof result.then === "function") {
          result.then(() => report("mermaid diagram rendered")).catch(err => {
            div.className = "error";
            div.textContent = "mermaid: " + String(err && err.message ? err.message : err);
            report("mermaid render FAILED: " + String(err && err.message ? err.message : err));
          });
        }
      } catch (e) {
        div.className = "error";
        div.textContent = "mermaid: " + (e && e.message ? e.message : String(e));
        report("mermaid run threw: " + (e && e.message ? e.message : String(e)));
      }
    });
  }
  addCopyButtons(el);
}

const chatEl = document.getElementById("chat");
function scrollBottom() { chatEl.scrollTop = chatEl.scrollHeight; }
function report(msg) { try { if (typeof window.__javaReport === "function") window.__javaReport(msg); } catch (e) {} }

function addUser(text) {
  const wrap = document.createElement("div"); wrap.className = "msg user";
  const bubble = document.createElement("div"); bubble.className = "bubble";
  renderMarkdown(bubble, text);
  wrap.appendChild(bubble); chatEl.appendChild(wrap); scrollBottom();
  report("user bubble rendered: " + text.slice(0, 60));
}

function addAssistant(messageId, reasoningText) {
  const wrap = document.createElement("div"); wrap.className = "msg assistant";
  wrap.dataset.mid = messageId || "";
  const bubble = document.createElement("div"); bubble.className = "bubble";
  if (reasoningText && reasoningText.trim().length > 0) {
    const details = document.createElement("details"); details.className = "reasoning";
    const summary = document.createElement("summary"); summary.textContent = "reasoning";
    const rbody = document.createElement("div"); renderMarkdown(rbody, reasoningText);
    details.appendChild(summary); details.appendChild(rbody); bubble.appendChild(details);
  }
  const body = document.createElement("div"); body.className = "body";
  bubble.appendChild(body);
  wrap.appendChild(bubble); chatEl.appendChild(wrap); scrollBottom();
  return { wrap, bubble, body };
}

function findAssistant(messageId) {
  if (!messageId) return null;
  return chatEl.querySelector('.msg.assistant[data-mid="' + cssEscape(messageId) + '"]') || null;
}
function cssEscape(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, "_"); }

// ---- tool parts -------------------------------------------------------------
// Assistant messages can carry tool-call parts (name + coarse state). They are
// rendered as compact monospace lines "tool: <name> — <state>", inserted before
// the body. Built with createElement/textContent only (never innerHTML), so a
// hostile tool name cannot inject markup.
const TOOL_LABELS = { running: "running…", completed: "completed ✓", error: "error ✗" };

function renderToolLines(container, tools, before) {
  if (!container || !Array.isArray(tools) || tools.length === 0) return 0;
  let rendered = 0;
  tools.forEach(t => {
    if (!t || typeof t !== "object") return;
    const name = (typeof t.name === "string" && t.name) ? t.name : "unknown";
    const state = typeof t.state === "string" ? t.state : "";
    const known = Object.prototype.hasOwnProperty.call(TOOL_LABELS, state);
    const line = document.createElement("div");
    line.className = "tool-line tool-" + (known ? state : "unknown");
    line.textContent = "tool: " + name + " — " + (known ? TOOL_LABELS[state] : (state || "unknown"));
    container.insertBefore(line, before || null);
    rendered++;
  });
  if (rendered > 0) report(rendered + " tool line(s) rendered");
  return rendered;
}

// ---- Java bridge (called via browser.execute) ----
// Payloads are JSON *strings* (Java: ChatScripts). Objects are accepted too, so a
// call built by hand (window.__appendUser({...})) cannot silently do nothing.
function payload(arg) {
  if (typeof arg === "string") return JSON.parse(arg);
  if (arg && typeof arg === "object") return arg;
  return {};
}

// Every bridge call is guarded: JS errors are reported to Java (and the call
// returns false) instead of failing silently inside browser.execute().
function guard(name, fn) {
  return function (arg) {
    try {
      return fn(arg);
    } catch (e) {
      report("JS ERROR in " + name + ": " + (e && e.message ? e.message : String(e)));
      return false;
    }
  };
}

window.onerror = function (message, source, line, col) {
  report("JS ERROR: " + message + " @" + line + ":" + col);
  return false;
};
window.addEventListener("unhandledrejection", function (e) {
  report("JS REJECTION: " + String(e && e.reason ? e.reason : e));
});

window.__setTheme = guard("__setTheme", function (theme) {
  const name = typeof theme === "string" ? theme : String(theme);
  const dark = name === "dark";
  document.body.classList.toggle("dark", dark);
  document.body.classList.toggle("light", !dark);
  // swap the highlight.js theme with the IDE theme
  const lightCss = document.getElementById("hljs-light");
  const darkCss = document.getElementById("hljs-dark");
  if (lightCss && darkCss) {
    lightCss.disabled = dark;
    darkCss.disabled = !dark;
  }
  mermaidReady = false; // re-init lazily with the right theme
  report("theme set: " + name);
  return true;
});

// ---- doc mode ---------------------------------------------------------------
// Read-only document mode for embedders that reuse this page as a markdown
// renderer (e.g. the Board's ticket details): drops the chat-bubble width cap
// so full-width content (mermaid architecture diagrams) fits. Applied
// automatically when the page is loaded with ?doc=1, or on demand from Java.
window.__setDocMode = guard("__setDocMode", function (on) {
  document.body.classList.toggle("doc", !!on);
  report("doc mode: " + (on ? "on" : "off"));
  return true;
});
try {
  if (typeof location !== "undefined" && /(?:[?&])doc=1(?:&|$)/.test(location.search || "")) {
    document.body.classList.add("doc");
  }
} catch (e) {
  // location unavailable (embedded shims): __setDocMode still works
}

window.__clear = guard("__clear", function () {
  chatEl.innerHTML = "";
  return true;
});

window.__setNotice = guard("__setNotice", function (text) {
  const div = document.createElement("div");
  div.className = "notice";
  div.textContent = typeof text === "string" ? text : String(text);
  chatEl.appendChild(div);
  scrollBottom();
  report("notice rendered: " + div.textContent.slice(0, 60));
  return true;
});

window.__setMessages = guard("__setMessages", function (json) {
  chatEl.innerHTML = "";
  const entries = payload(json);
  entries.forEach(e => {
    if (e.role === "user") {
      addUser(e.text);
    } else {
      const a = addAssistant(e.id, e.reasoning);
      renderToolLines(a.bubble, e.tools, a.body);
      renderMarkdown(a.body, e.text);
      if (e.meta) { const m = document.createElement("div"); m.className = "meta"; m.textContent = e.meta; a.bubble.appendChild(m); }
    }
  });
  scrollBottom();
  report("history rendered (" + entries.length + " entries)");
  return true;
});

window.__appendUser = guard("__appendUser", function (json) {
  const p = payload(json);
  addUser(typeof p.text === "string" ? p.text : "");
  return true;
});

window.__startAssistant = guard("__startAssistant", function (json) {
  const p = payload(json);
  if (!findAssistant(p.mid)) addAssistant(p.mid, null);
  return true;
});

window.__appendDelta = guard("__appendDelta", function (json) {
  const p = payload(json);
  let node = findAssistant(p.mid);
  if (!node) { addAssistant(p.mid, null); node = findAssistant(p.mid); }
  if (!node) return true;
  // A finalized bubble (abort / authoritative render) must never be re-opened:
  // its .stream-raw is gone, so appending would blank the rendered answer and
  // replace it with the single late chunk.
  if (node.classList.contains("stream-done")) return true;
  let body = node.querySelector(".body");
  let raw = body.querySelector(".stream-raw");
  if (!raw) { body.innerHTML = ""; raw = document.createElement("div"); raw.className = "stream-raw"; body.appendChild(raw); }
  raw.appendChild(document.createTextNode(p.text == null ? "" : String(p.text)));
  let cursor = raw.querySelector(".cursor");
  if (!cursor) { cursor = document.createElement("span"); cursor.className = "cursor"; }
  raw.appendChild(cursor);
  scrollBottom();
  return true;
});

window.__setAssistantText = guard("__setAssistantText", function (json) {
  const p = payload(json);
  const text = typeof p.text === "string" ? p.text : "";
  let node = findAssistant(p.mid);
  if (!node) { addAssistant(p.mid, p.reasoning || null); node = findAssistant(p.mid); }
  if (!node) return true;
  const body = node.querySelector(".body");
  // C--001: an EMPTY authoritative reply must never wipe text that already
  // streamed (live 2026-09-15: a 0-char final render blanked a streamed
  // answer). If the stream produced content, finalize THAT (same path as
  // __stopStream) instead of overwriting the body with nothing.
  if (text === "") {
    const raw = node.querySelector(".stream-raw");
    const streamed = raw ? raw.textContent : "";
    if (streamed.trim() !== "") {
      body.innerHTML = "";
      renderMarkdown(body, streamed);
      node.classList.add("stream-done");
      node.querySelectorAll(".cursor").forEach(c => c.remove());
      report("empty final reply - kept streamed text (" + streamed.length + " chars)");
      return true;
    }
  }
  body.innerHTML = "";
  renderMarkdown(body, text);
  // The authoritative render closes the bubble: a delta still in flight must
  // not overwrite it (see __appendDelta's stream-done guard).
  node.classList.add("stream-done");
  node.querySelectorAll(".cursor").forEach(c => c.remove());
  // re-render tool lines (a second authoritative render must not duplicate them)
  node.querySelectorAll(".tool-line").forEach(l => l.remove());
  const bubble = node.querySelector(".bubble");
  if (bubble) renderToolLines(bubble, p.tools, body);
  if (p.meta) { let m = node.querySelector(".meta"); if (!m) { m = document.createElement("div"); m.className = "meta"; node.querySelector(".bubble").appendChild(m); } m.textContent = p.meta; }
  scrollBottom();
  report("assistant bubble rendered (" + text.length + " chars, meta=" + (p.meta || "") + ")");
  return true;
});

// Stops the streaming cursor of one bubble: the host calls this when the send
// completed, failed or was aborted. If the bubble is still in raw streaming
// form (no authoritative render arrived — e.g. an intermediate tool-round
// message, or the POST reply came back empty), the accumulated raw text is
// finalized into rendered markdown, so a streamed table never stays raw ASCII.
// Harmless when the bubble or cursor is absent (idempotent); an authoritative
// __setAssistantText afterwards still replaces the whole body.
window.__stopStream = guard("__stopStream", function (json) {
  const p = payload(json);
  const node = findAssistant(p.mid);
  if (!node) return true;
  if (!node.classList.contains("stream-done")) {
    const raw = node.querySelector(".stream-raw");
    const body = node.querySelector(".body");
    if (raw && body) {
      const text = raw.textContent; // the cursor span carries no text
      body.innerHTML = "";
      renderMarkdown(body, text);
      report("stream finalized (" + text.length + " chars)");
    }
    node.classList.add("stream-done");
  }
  node.querySelectorAll(".cursor").forEach(c => c.remove());
  return true;
});

// ---- copy-code ---------------------------------------------------------------
// Every rendered code fence gets a Copy button (top-right of the fence, styled
// by the page theme, no external assets). It copies the RAW code text via the
// async clipboard API, falling back to execCommand + an offscreen textarea.
// window.__copyCode is exposed so hosts/tests can drive the click path where
// the clipboard is unavailable; outcomes are always reported to __javaReport.
function addCopyButtons(el) {
  el.querySelectorAll("pre").forEach(pre => {
    const code = pre.querySelector("code");
    if (!code) return;
    if (code.classList.contains("language-mermaid")) return; // a diagram, not code
    if (pre.querySelector(".copy-btn")) return; // idempotent on re-renders
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy-btn";
    btn.textContent = "Copy";
    pre.appendChild(btn);
    btn.addEventListener("click", function () { window.__copyCode(btn); });
  });
}

function rawCodeOf(button) {
  const pre = button ? button.parentElement : null;
  const code = pre ? pre.querySelector("code") : null;
  return code ? code.textContent : (pre ? pre.textContent : "");
}

function copyToClipboard(text, done) {
  if (typeof navigator !== "undefined" && navigator.clipboard
      && typeof navigator.clipboard.writeText === "function") {
    try {
      const result = navigator.clipboard.writeText(text);
      if (result && typeof result.then === "function") {
        result.then(function () { done(true); },
                    function () { done(fallbackCopy(text)); });
        return;
      }
    } catch (e) {
      report("clipboard API failed: " + (e && e.message ? e.message : String(e)));
    }
  }
  done(fallbackCopy(text));
}

function fallbackCopy(text) {
  try {
    if (typeof document.execCommand !== "function") return false;
    const ta = document.createElement("textarea");
    ta.value = text;
    if (ta.style) { ta.style.position = "fixed"; ta.style.top = "0"; ta.style.left = "-9999px"; }
    document.body.appendChild(ta);
    if (ta.select) { ta.select(); }
    const ok = document.execCommand("copy");
    if (ta.parentElement && ta.parentElement.removeChild) { ta.parentElement.removeChild(ta); }
    return ok === true;
  } catch (e) {
    report("copy fallback failed: " + (e && e.message ? e.message : String(e)));
    return false;
  }
}

function confirmCopied(button) {
  if (!button || !button.classList) return;
  button.textContent = "Copied";
  button.classList.add("copied");
  setTimeout(function () {
    button.textContent = "Copy";
    button.classList.remove("copied");
  }, 1500);
}

window.__copyCode = guard("__copyCode", function (button) {
  const text = rawCodeOf(button);
  copyToClipboard(text, function (ok) {
    // report lengths only - code fences can hold secrets, never log their content
    if (ok) {
      report("code copied (" + text.length + " chars)");
      confirmCopied(button);
    } else {
      report("copy unavailable (" + text.length + " chars)");
    }
  });
  return true;
});

// ---- links -----------------------------------------------------------------
// Links must never navigate this view: the chat page would be replaced by the
// target site and the whole transcript would be gone. Every link is handed to
// Eclipse, which opens it in the external browser.
function linkTargetOf(node) {
  while (node && node !== chatEl) {
    if (node.tagName === "A") return node;
    node = node.parentElement;
  }
  return null;
}

// exposed for the automated bridge test (same function the listener uses)
window.__linkClick = function (event) {
  const anchor = linkTargetOf(event && event.target);
  if (!anchor) return false;
  const href = anchor.getAttribute ? anchor.getAttribute("href") : null;
  if (!href || href.charAt(0) === "#") return false;
  if (event.preventDefault) event.preventDefault();
  if (typeof window.__javaOpenExternal === "function") {
    window.__javaOpenExternal(href);
  } else {
    report("external link (no Java bridge): " + href);
  }
  return true;
};
chatEl.addEventListener("click", window.__linkClick);

// The page announces readiness to Java (authoritative signal - flushes queued renders).
report("page-ready");
