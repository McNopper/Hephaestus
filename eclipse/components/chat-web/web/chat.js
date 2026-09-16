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

/**
 * Renders markdown (math extracted first, fences highlighted, copy buttons).
 * With `{live: true}` - the progressive streaming pass - the mermaid diagram
 * pass is skipped: a still-streaming fence holds incomplete source that would
 * error on every throttle tick. Live fences stay highlighted code; the final
 * render (stream stop or authoritative text) runs the diagram pass.
 */
function renderMarkdown(el, text, opts) {
  const live = !!(opts && opts.live);
  const extracted = extractMath(text || "");
  el.innerHTML = restoreMath(md.render(extracted.source), extracted.spans);
  // mermaid diagrams
  const mermaidBlocks = live ? [] : el.querySelectorAll("pre > code.language-mermaid");
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

function addUser(text, mid) {
  const wrap = document.createElement("div"); wrap.className = "msg user";
  if (mid) wrap.dataset.mid = mid;
  const bubble = document.createElement("div"); bubble.className = "bubble";
  renderMarkdown(bubble, text);
  wrap.appendChild(bubble); addForkButton(wrap, mid); chatEl.appendChild(wrap); scrollBottom();
  report("user bubble rendered: " + text.slice(0, 60));
}

function addAssistant(messageId, reasoningText) {
  const wrap = document.createElement("div"); wrap.className = "msg assistant";
  wrap.dataset.mid = messageId || "";
  const bubble = document.createElement("div"); bubble.className = "bubble";
  if (reasoningText && reasoningText.trim().length > 0) {
    const details = document.createElement("details"); details.className = "reasoning";
    const summary = document.createElement("summary"); summary.textContent = "reasoning";
    const rbody = document.createElement("div"); rbody.className = "reasoning-body";
    renderMarkdown(rbody, reasoningText);
    details.appendChild(summary); details.appendChild(rbody); bubble.appendChild(details);
  }
  const body = document.createElement("div"); body.className = "body";
  bubble.appendChild(body);
  wrap.appendChild(bubble); addForkButton(wrap, messageId); chatEl.appendChild(wrap); scrollBottom();
  return { wrap, bubble, body };
}

// ---- fork-at-message (TUI parity) --------------------------------------------
// Every message the server assigned an id for (history rows of both roles, and
// streamed assistant bubbles) gets a hover Fork button: it hands that message
// id to the host (__javaForkAt), which forks the session AT the message and
// switches to the fork. A live user echo carries no id yet (the server has not
// assigned one), so it gets no button until the history reloads.
function addForkButton(wrap, mid) {
  if (!wrap || !mid) return;
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "fork-btn";
  btn.title = "Fork the session at this message";
  btn.textContent = "⑂ Fork here";
  btn.addEventListener("click", function (event) {
    if (event && event.stopPropagation) event.stopPropagation();
    const at = wrap.dataset.mid || mid;
    if (typeof window.__javaForkAt === "function") {
      window.__javaForkAt(at);
      report("fork requested at " + at);
    } else {
      report("fork requested at " + at + " but no Java bridge");
    }
  });
  wrap.appendChild(btn);
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

// ---- reasoning visibility -----------------------------------------------------
// The host toggles whether thinking/reasoning progress is visible (user
// direction 2026-09-16). A body class hides the collapsible details blocks -
// both live-streamed and history-rendered - without touching their content,
// so toggling back on is always complete and needs no re-render.
window.__setReasoningVisible = guard("__setReasoningVisible", function (json) {
  const p = payload(json);
  document.body.classList.toggle("hide-reasoning", !(p.visible === undefined ? true : !!p.visible));
  report("reasoning visible: " + (p.visible === undefined ? true : !!p.visible));
  return true;
});

function forgetStreams() {
  for (const state of Array.from(streams.values())) cancelScheduledRender(state);
  streams.clear();
  lastStreamMid = null;
}

// ---- processing spinner ----------------------------------------------------
// The opencode TUI's circling-squares language (user direction 2026-09-16):
// one spinner element reused for the streaming cursor and the waiting
// indicator instead of the old blinking block / bouncing dots.
function makeSpinner(extraClass) {
  const span = document.createElement("span");
  span.className = "spin" + (extraClass ? " " + extraClass : "");
  span.innerHTML = "<i></i><i></i><i></i><i></i>";
  return span;
}

// ---- waiting indicator -----------------------------------------------------
// The submit -> first response token gap can take seconds (queueing, session
// creation, model latency). The moment the prompt echoes (__appendUser - the
// live-submit signal; history replay goes through __setMessages instead) an
// animated-dots placeholder bubble makes the wait visible. Cleared by the
// first assistant bubble (__startAssistant), any notice (send failures,
// aborts), __stopStream, and the transcript wipes (__clear, __setMessages).
let waitingEl = null;
function showWaiting() {
  if (waitingEl) return;
  const div = document.createElement("div");
  div.className = "msg assistant waiting";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.appendChild(makeSpinner(null));
  div.appendChild(bubble);
  chatEl.appendChild(div);
  waitingEl = div;
  scrollBottom();
}
function hideWaiting() {
  if (waitingEl) { waitingEl.remove(); waitingEl = null; }
}

window.__clear = guard("__clear", function () {
  chatEl.innerHTML = "";
  hideWaiting();
  forgetStreams(); // pending progressive ticks no-op (their mid is gone)
  return true;
});

window.__setNotice = guard("__setNotice", function (text) {
  hideWaiting(); // notices are the terminal signal on error and abort paths
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
  hideWaiting();
  forgetStreams(); // pending progressive ticks no-op (their mid is gone)
  const entries = payload(json);
  entries.forEach(e => {
    if (e.role === "user") {
      addUser(e.text, e.id);
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
  showWaiting();
  return true;
});

window.__startAssistant = guard("__startAssistant", function (json) {
  hideWaiting(); // the reply has arrived - hand over to the real bubble
  const p = payload(json);
  if (!findAssistant(p.mid)) {
    const a = addAssistant(p.mid, null);
    // visible until the first content chunk paints (cleared by the first
    // progressive tick, __stopStream and the final render)
    const thinking = document.createElement("div");
    thinking.className = "thinking";
    thinking.textContent = "thinking…";
    a.bubble.insertBefore(thinking, a.body);
    scrollBottom();
  }
  return true;
});

// ---- streaming (block-level progressive markdown) ----------------------------
// While a reply streams, the accumulated text is split into markdown blocks
// (blank-line separated, fence and $$ aware). A block that is certainly
// finished - sealed by a later separator, a closed code fence or a well-formed
// table - renders as markdown IMMEDIATELY as its own element and is never
// touched again; only the trailing in-progress block stays raw text under the
// blinking cursor. Raw-tail repaints are throttled (~5 renders/s, leading edge
// immediate, trailing chunks coalesced) so a fast token stream cannot flood
// the SWT host; a structural commit (blocks that just completed) paints on the
// spot because its cost scales with the new blocks, not the whole message.
// Reasoning streams into the collapsible details while generating. The
// authoritative-render semantics are unchanged: __setAssistantText replaces
// the body, __stopStream finalizes it, and the stream-done guard keeps late
// deltas from re-opening a closed bubble.
const STREAM_RENDER_MS = 200; // 5 renders/s (target band: 4-10)
const streams = new Map();    // mid -> { mid, text, reasoning, lastRender, timer, pending, committed, divs, tailDiv, tailText, rawEl }
// Only the NEWEST stream carries the blinking cursor (user direction
// 2026-09-16): a tool round spawns several bubbles, and each blinking made
// the transcript flicker in three places at once.
let lastStreamMid = null;

function streamState(mid) {
  let state = streams.get(mid);
  if (!state) {
    // a new stream demotes the previous one: strip its cursor now (its next
    // throttled repaint may never come - the old bubble is typically done)
    if (lastStreamMid !== null && lastStreamMid !== mid) {
      const prev = findAssistant(lastStreamMid);
      if (prev) prev.querySelectorAll(".cursor").forEach(c => c.remove());
    }
    lastStreamMid = mid;
    state = { mid: mid, text: "", reasoning: "", lastRender: 0, timer: 0, pending: false,
        committed: 0, divs: [], tailDiv: null, tailText: null, rawEl: null };
    streams.set(mid, state);
  }
  return state;
}

function cancelScheduledRender(state) {
  if (state.timer) { clearTimeout(state.timer); state.timer = 0; }
  state.pending = false;
}

/** Drops one stream's state (authoritative render / history wipe). */
function dropStream(mid) {
  const state = streams.get(mid);
  if (state) cancelScheduledRender(state);
  streams.delete(mid);
  if (lastStreamMid === mid) lastStreamMid = null;
}

/** true when the line closes an open fence of the given marker run. */
function fenceCloses(line, fence) {
  return new RegExp("^\\s*[" + fence.charAt(0) + "]{" + fence.length + ",}\\s*$").test(line);
}

/**
 * Splits text into markdown blocks at blank-line separators (blank lines
 * inside code fences and $$ display math do not separate). @return
 * {blocks, closed} where closed means the text ends at a block boundary -
 * there is no trailing in-progress block.
 */
function splitMarkdownBlocks(text) {
  const lines = String(text == null ? "" : text).split("\n");
  const blocks = [];
  let current = [];
  let inFence = false, fence = "";
  let inMath = false;
  let sealed = true;
  for (const line of lines) {
    if (inFence) {
      current.push(line);
      if (fenceCloses(line, fence)) inFence = false;
      sealed = false;
      continue;
    }
    if (line.trim() === "" && !inMath) {
      if (current.length > 0) { blocks.push(current.join("\n")); current = []; }
      sealed = true;
      continue;
    }
    const opened = /^ {0,3}(`{3,}|~{3,})/.exec(line);
    if (opened) { inFence = true; fence = opened[1]; }
    inMath = mathSpanOpen(line, inMath);
    current.push(line);
    sealed = false;
  }
  if (current.length > 0) blocks.push(current.join("\n"));
  return { blocks: blocks, closed: sealed };
}

/** Flips inMath for the $$ pairs this line opens/closes outside `code`. */
function mathSpanOpen(line, inMath) {
  let dollars = 0;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === "`") {
      const run = inlineCodeEnd(line, i);
      if (run > 0) { i = run - 1; continue; }
    }
    if (ch === "$" && line[i + 1] === "$") { dollars++; i++; }
  }
  return dollars % 2 === 1 ? !inMath : inMath;
}

/** A well-formed table block (header + delimiter row, pipes aligned). */
function isTableBlock(block) {
  const lines = block.split("\n").filter(l => l.trim() !== "");
  if (lines.length < 2) return false;
  const pipes = l => (l.match(/\|/g) || []).length;
  for (const line of lines) {
    if (!line.includes("|")) return false;
  }
  const delimiter = lines[1];
  if (!/^[\s|:-]+$/.test(delimiter) || !delimiter.includes("-")) return false;
  return pipes(lines[0]) === pipes(delimiter) && pipes(lines[0]) > 0;
}

/**
 * A trailing block that is structurally finished even without a following
 * separator: a closed code fence, or a table (rows may still append - it
 * re-renders per tick, which still scales with one block, not the message).
 */
function blockSelfComplete(block) {
  const opened = /^ {0,3}(`{3,}|~{3,})/.exec(block);
  if (opened) {
    return block.split("\n").slice(1).some(l => fenceCloses(l, opened[1]));
  }
  return isTableBlock(block);
}

/** Removes the pre-content "thinking…" indicator. */
function removeThinking(node) {
  const t = node.querySelector(".thinking");
  if (t) t.remove();
}

/** Streams reasoning into the collapsible details (live: plain text). */
function syncStreamReasoning(node, state, body) {
  const text = state.reasoning || "";
  if (text.trim() === "") return;
  let details = node.querySelector("details.reasoning");
  if (!details) {
    details = document.createElement("details");
    details.className = "reasoning";
    const summary = document.createElement("summary");
    summary.textContent = "thinking";
    const rbody = document.createElement("div");
    rbody.className = "reasoning-body";
    details.appendChild(summary);
    details.appendChild(rbody);
    node.querySelector(".bubble").insertBefore(details, body);
  }
  const rbody = details.querySelector(".reasoning-body");
  if (rbody && rbody.textContent !== text) rbody.textContent = text;
}

/** Paints one progressive tick of a streaming bubble; false when impossible. */
function renderStreamTick(mid) {
  const state = streams.get(mid);
  if (!state) return false;
  const node = findAssistant(mid);
  if (!node || node.classList.contains("stream-done")) return false;
  const body = node.querySelector(".body");
  if (!body) return false;
  removeThinking(node);
  syncStreamReasoning(node, state, body);
  const split = splitMarkdownBlocks(state.text);
  const blocks = split.blocks;
  const hardCount = Math.max(0, split.closed ? blocks.length : blocks.length - 1);
  while (state.committed < hardCount) {
    // a live-rendered trailing block (tailDiv) that just got sealed is
    // promoted in place - it IS the block at index `committed`
    let div = state.tailDiv;
    if (div) {
      state.tailDiv = null;
      state.tailText = null;
    } else {
      div = document.createElement("div");
      div.className = "stream-block";
    }
    renderMarkdown(div, blocks[state.committed], { live: true });
    state.divs.push(div);
    body.insertBefore(div, state.rawEl || null);
    state.committed++;
  }
  const trailing = blocks.length > 0 ? blocks[blocks.length - 1] : "";
  const tailLive = !split.closed && blocks.length > 0 && blockSelfComplete(trailing);
  if (tailLive) {
    if (!state.tailDiv) {
      state.tailDiv = document.createElement("div");
      state.tailDiv.className = "stream-block";
      state.tailText = null;
      body.insertBefore(state.tailDiv, state.rawEl || null);
    }
    if (state.tailText !== trailing) {
      renderMarkdown(state.tailDiv, trailing, { live: true });
      state.tailText = trailing;
    }
  } else if (state.tailDiv) {
    state.tailDiv.remove(); // defensive: cannot go complete -> incomplete
    state.tailDiv = null;
    state.tailText = null;
  }
  const needsRaw = !tailLive && !split.closed && trailing.trim() !== "";
  if (needsRaw) {
    if (!state.rawEl) {
      state.rawEl = document.createElement("div");
      state.rawEl.className = "stream-raw";
      body.appendChild(state.rawEl);
    }
    state.rawEl.textContent = trailing;
  } else if (state.rawEl) {
    state.rawEl.remove();
    state.rawEl = null;
  }
  // the cursor rides at the very end (inside the raw tail when there is one)
  // and only on the newest stream - older bubbles keep their committed text
  // without blinking (user direction 2026-09-16)
  if (lastStreamMid === state.mid) {
    let cursor = body.querySelector(".cursor");
    if (!cursor) { cursor = makeSpinner("cursor"); }
    (state.rawEl || body).appendChild(cursor);
  }
  state.lastRender = Date.now();
  scrollBottom();
  return true;
}

/** true when a block just completed (or the live trailing block changed). */
function hasStructuralChange(state) {
  const split = splitMarkdownBlocks(state.text);
  const blocks = split.blocks;
  const hardCount = Math.max(0, split.closed ? blocks.length : blocks.length - 1);
  if (hardCount > state.committed) return true;
  if (!split.closed && blocks.length > 0) {
    const trailing = blocks[blocks.length - 1];
    if (blockSelfComplete(trailing) && state.tailText !== trailing) return true;
  }
  return false;
}

/** Throttled progressive paint; structural commits bypass the coalescing. */
function scheduleStreamRender(mid) {
  const state = streams.get(mid);
  if (!state) return;
  if (hasStructuralChange(state)) {
    renderStreamTick(mid);
    cancelScheduledRender(state);
    return;
  }
  const since = Date.now() - state.lastRender;
  if (since >= STREAM_RENDER_MS) {
    renderStreamTick(mid);
    return;
  }
  if (state.pending) return;
  state.pending = true;
  state.timer = setTimeout(function () {
    state.pending = false;
    renderStreamTick(mid);
  }, STREAM_RENDER_MS - since);
}

window.__appendDelta = guard("__appendDelta", function (json) {
  const p = payload(json);
  let node = findAssistant(p.mid);
  if (!node) { addAssistant(p.mid, null); node = findAssistant(p.mid); }
  if (!node) return true;
  // A finalized bubble (abort / authoritative render) must never be re-opened:
  // its render is authoritative, so appending would blank the rendered answer
  // and replace it with the single late chunk.
  if (node.classList.contains("stream-done")) return true;
  const state = streamState(p.mid);
  state.text += (p.text == null ? "" : String(p.text));
  scheduleStreamRender(p.mid);
  return true;
});

window.__appendReasoningDelta = guard("__appendReasoningDelta", function (json) {
  const p = payload(json);
  let node = findAssistant(p.mid);
  if (!node) { addAssistant(p.mid, null); node = findAssistant(p.mid); }
  if (!node) return true;
  if (node.classList.contains("stream-done")) return true;
  const state = streamState(p.mid);
  state.reasoning = (state.reasoning || "") + (p.text == null ? "" : String(p.text));
  scheduleStreamRender(p.mid);
  return true;
});

// Test/diagnostic hook: executes mid's PENDING progressive tick right now
// (the checks drive the throttle deterministically with it; a second flush
// with nothing scheduled is a harmless no-op returning false).
window.__flushStream = guard("__flushStream", function (mid) {
  const key = typeof mid === "string" ? mid : String(mid);
  const state = streams.get(key);
  if (!state || !state.pending) return false;
  cancelScheduledRender(state);
  return renderStreamTick(key);
});

/**
 * Final-render reasoning sync: the authoritative text wins (markdown), an
 * EMPTY final reasoning never wipes reasoning that streamed (C--001 spirit),
 * and streaming "thinking" details upgrade to a "reasoning" block.
 */
function syncFinalReasoning(node, reasoning, streamedReasoning, body) {
  const text = reasoning && reasoning.trim() !== "" ? reasoning : (streamedReasoning || "");
  if (text.trim() === "") return;
  let details = node.querySelector("details.reasoning");
  if (!details) {
    details = document.createElement("details");
    details.className = "reasoning";
    const summary = document.createElement("summary");
    summary.textContent = "reasoning";
    details.appendChild(summary);
    node.querySelector(".bubble").insertBefore(details, body);
  }
  const summary = details.querySelector("summary");
  if (summary) summary.textContent = "reasoning";
  details.querySelectorAll(".reasoning-body").forEach(r => r.remove());
  const rbody = document.createElement("div");
  rbody.className = "reasoning-body";
  renderMarkdown(rbody, text);
  details.appendChild(rbody);
}

window.__setAssistantText = guard("__setAssistantText", function (json) {
  hideWaiting(); // a reply that never streamed still terminates the wait
  const p = payload(json);
  const text = typeof p.text === "string" ? p.text : "";
  const reasoning = typeof p.reasoning === "string" ? p.reasoning : "";
  let node = findAssistant(p.mid);
  if (!node) { addAssistant(p.mid, reasoning || null); node = findAssistant(p.mid); }
  if (!node) return true;
  // Capture what the stream accumulated before closing it: the C--001 guard
  // and the reasoning fallback below need it.
  const state = streams.get(p.mid);
  const streamedText = state ? state.text : "";
  const streamedReasoning = state ? (state.reasoning || "") : "";
  // The authoritative render closes the bubble: cancel any pending
  // progressive tick and forget the accumulated text.
  dropStream(p.mid);
  const body = node.querySelector(".body");
  // C--001: an EMPTY authoritative reply must never wipe text that already
  // streamed (live 2026-09-15: a 0-char final render blanked a streamed
  // answer). If the stream (or an already-finalized body) produced content,
  // keep THAT instead of overwriting the body with nothing.
  if (text === "" && (streamedText.trim() !== "" || body.textContent.trim() !== "")) {
    if (streamedText.trim() !== "") {
      body.innerHTML = "";
      renderMarkdown(body, streamedText);
    }
    node.classList.add("stream-done");
    node.querySelectorAll(".cursor").forEach(c => c.remove());
    removeThinking(node);
    syncFinalReasoning(node, reasoning, streamedReasoning, body);
    report("empty final reply - kept streamed text ("
        + (streamedText.trim() !== "" ? streamedText : body.textContent).length + " chars)");
    scrollBottom();
    return true;
  }
  body.innerHTML = "";
  renderMarkdown(body, text);
  // and a delta still in flight must not overwrite it either (see
  // __appendDelta's stream-done guard)
  node.classList.add("stream-done");
  node.querySelectorAll(".cursor").forEach(c => c.remove());
  removeThinking(node);
  syncFinalReasoning(node, reasoning, streamedReasoning, body);
  // re-render tool lines (a second authoritative render must not duplicate them)
  node.querySelectorAll(".tool-line").forEach(l => l.remove());
  const bubble = node.querySelector(".bubble");
  if (bubble) renderToolLines(bubble, p.tools, body);
  if (p.meta) { let m = node.querySelector(".meta"); if (!m) { m = document.createElement("div"); m.className = "meta"; node.querySelector(".bubble").appendChild(m); } m.textContent = p.meta; }
  scrollBottom();
  report("assistant bubble rendered (" + text.length + " chars, meta=" + (p.meta || "") + ")");
  return true;
});

// Stops the streaming of one bubble: the host calls this when the send
// completed, failed or was aborted. The accumulated text is finalized with the
// FULL render pipeline (mermaid pass included), so a still-throttled tail
// chunk never leaves the bubble partial, and streamed reasoning upgrades to
// markdown. Harmless when the bubble or cursor is absent (idempotent); an
// authoritative __setAssistantText afterwards still replaces the whole body.
window.__stopStream = guard("__stopStream", function (json) {
  hideWaiting(); // a stream ending without a proper start must not strand it
  const p = payload(json);
  const node = findAssistant(p.mid);
  const state = streams.get(p.mid);
  if (state) cancelScheduledRender(state);
  if (node && !node.classList.contains("stream-done")) {
    const body = node.querySelector(".body");
    removeThinking(node);
    // progressive ticks already showed markdown block by block, but a
    // throttled tick may be pending - render the FULL accumulated text
    if (body && state && state.text.length > 0) {
      renderMarkdown(body, state.text);
      report("stream finalized (" + state.text.length + " chars)");
    }
    if (state && (state.reasoning || "").trim() !== "") {
      syncFinalReasoning(node, "", state.reasoning, body);
    }
    node.classList.add("stream-done");
  }
  if (node) node.querySelectorAll(".cursor").forEach(c => c.remove());
  streams.delete(p.mid);
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
