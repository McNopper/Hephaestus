# chat-web

A standalone, host-embeddable chat renderer: one static page (`web/chat.html`
plus `web/chat.js` and vendored libraries) that renders a chat transcript —
markdown, KaTeX math, mermaid diagrams, highlight.js code blocks — and exposes
a small JavaScript bridge.

It is plain HTML/JS/CSS with **no build step, no Node runtime dependency, no
OSGi and no Java**. Any host that can (a) serve the `web/` directory as static
files and (b) evaluate JavaScript strings in the page (Eclipse SWT
`Browser.execute`, a WebView `evaluateJavascript`, a test harness, …) can host
it. In this repository the Eclipse bundle `com.opencode.ide.chat` consumes it
at build time by copying `web/` into the plugin jar; that is one consumer, not
a requirement.

## Layout

```
components/chat-web/
  web/                     the component itself (serve this directory as-is)
    chat.html              entry page, loads everything below
    chat.js                renderer + host bridge (the contract lives here)
    markdown-it.min.js     markdown (vendored)
    mermaid.min.js         diagrams (vendored, esbuild IIFE)
    katex/                 math: katex.min.js, katex.min.css, fonts/
    hljs/                  code highlighting: highlight.min.js, cmake.min.js,
                           github.min.css + github-dark.min.css (theme pair)
  renderer-check.mjs       check: assets present, vendor libs actually render
  bridge-check.mjs         check: executes the bridge against a DOM shim
                            (incl. tool lines, copy-code paths, block-level
                            progressive streaming and the thinking indicator)
  mermaid-check.mjs        check: renders diagrams in real headless Edge
                            (SKIPs when Edge/puppeteer-core are absent)
  package.json             `npm run check` runs the checks
```

## The bridge contract

The host talks to the page by evaluating JS that calls `window.__*` functions.
Payload arguments are **JSON strings** (single-quoted JS string literals of
JSON), e.g. `window.__appendUser("{\"text\":\"hello\"}")`. For convenience the
functions also accept a plain object literal — but the JSON-string form is the
authoritative contract (it is what the Java side emits).

Every bridge function is guarded: it returns `true` on success and `false` on
error, and errors are reported to the host (see `__javaReport` below) instead
of throwing silently inside `browser.execute()`.

### Host → page

| Call | Payload | Effect |
|---|---|---|
| `__setTheme(theme)` | plain string `"dark"` or `"light"` | Toggles `body.dark`/`body.light`, swaps the highlight.js stylesheet (`#hljs-light`/`#hljs-dark`), re-initialises mermaid lazily with the matching theme. |
| `__setNotice(text)` | plain string | Appends a centred, muted notice line to the transcript. |
| `__appendUser(json)` | `{"text": string}` | Appends a user bubble; text is rendered as markdown. |
| `__startAssistant(json)` | `{"mid": string}` | Appends an empty assistant bubble tagged `data-mid=mid` showing a pulsing "thinking…" indicator (idempotent: no-op if `mid` already exists). The indicator is cleared by the first content chunk, `__stopStream` or the final render. |
| `__appendDelta(json)` | `{"mid": string, "text": string}` | Streams one text chunk into the assistant bubble. Rendering is **block-level progressive**: a markdown block that completed while streaming (sealed by a later blank line, a closed code fence, or a well-formed table) renders as markdown immediately as its own element and is never re-rendered; only the trailing in-progress block stays raw text (monospace) under the blinking cursor. Whole repaints are throttled to ~5 renders/s (leading edge immediate, later chunks coalesced); structural commits paint on the spot because their cost scales with the new blocks, not the whole message. The mermaid diagram pass is skipped mid-stream (incomplete fence source would error on every tick) — fences stay highlighted code until the final render. Creates the bubble if `__startAssistant` was not called. |
| `__appendReasoningDelta(json)` | `{"mid": string, "text": string}` | Streams one reasoning chunk into the bubble's collapsible `details.reasoning` block (summary "thinking") while the reply generates — plain text live, upgraded to markdown by the final render. Creates the bubble on demand; dropped on a finalized (`stream-done`) bubble like text deltas. |
| `__flushStream(mid)` | plain string | Test/diagnostic hook: executes `mid`'s pending progressive tick right now, so checks can drive the throttle deterministically. Returns `false` when nothing is pending (no-op). Hosts never need to call it. |
| `__setAssistantText(json)` | `{"mid": string, "text": string, "reasoning"?: string, "meta"?: string, "tools"?: [{"name": string, "state": string}, …]}` | Final authoritative render of the assistant bubble: markdown body (replacing streamed raw text), optional collapsible `reasoning` block, optional `meta` model label, optional compact tool-call lines (`tool: name — state`, state-colored: running pulses, completed dimmed, error red) above the body. Tool names are escaped — hostile input renders inert. |
| `__setMessages(json)` | JSON string of an array `[{"role":"user"\|"assistant","id":string,"text":string,"reasoning":string,"meta":string,"tools":[…]}, …]` | Replaces the whole transcript (history/resume load; `tools` optional per entry, same rendering as above). |
| `__setInboxItems(json)` | JSON string of an array `[{"id":string,"text":string}, …]` | Replaces the **composer queue row** (T-005 management surface): the prompts parked in the session's server-side inbox (the host's *Send to Queue*, v2 Alt+Enter). One line per pending prompt, each with **Steer now** / **Deliver next** / **Cancel** buttons that call `__javaInboxAction`. The row is pinned below the transcript (outside `#chat`, so transcript wipes never eat it) and hidden while empty. Prompt text is set via `textContent` — hostile input cannot inject markup. |
| `__setReasoningVisible(json)` | `{"visible": boolean}` | Toggles the visibility of thinking/reasoning blocks (live and history): a `body.hide-reasoning` class hides them — content stays in the DOM, so toggling back needs no re-render. The host's `/thinking` command and its toolbar toggle drive this; the host re-applies the value after every history render so the preference survives across messages. |
| `__setFileQuery(json)` | `{"query": string}` | Opens the **@-autocomplete** row (U-012): the host detected an `@`-token in its composer and hands over the text after the `@`. The page shows a searching row and asks Java for matches via `__javaFileQuery`. |
| `__setFileCompletions(json)` | `{"aliases": [{"id": string, "name": string}, …], "paths": string[], "selected": number}` | Renders the @-autocomplete's two groups (U-012 + U-047): **alias reference roots** (the server's reference catalog — possibly empty, then files only, no error) ABOVE the fuzzy-matched files, with group headers when both are present. Row `selected` is highlighted across BOTH groups (aliases first — the host owns keyboard navigation, its input has the focus). Both lists empty closes the row. Rows render via `textContent` only. |
| `__hideFileCompletions()` | none | Closes the @-autocomplete row (token left, Esc, picked or submitted). |
| `__setForms(json)` | JSON string of an array `[{"id": string, "title": string, "fields": […raw field objects…]}, …]` | Replaces the **question forms** area (U-014): one answerable card per form the session's run raised — title, leniently-rendered fields, **Submit**/**Cancel**. The service owns the field schema (a union of String/Number/Integer/Boolean/Multiselect/External field objects), so fields render by whatever discriminator/label/options keys they carry (union wrappers like `{"string": {...}}` are unwrapped; booleans → checkbox, multiselect → one checkbox per option, number/integer → number input, everything else → text input). The cards live outside `#chat` (transcript wipes never eat them) and persist until the host re-pushes without them — an unanswered ask is always visible. All content is `textContent`-only. |
| `__stopStream(json)` | `{"mid": string}` | Removes the streaming cursor from the bubble (host calls this when the send completed, failed or was aborted) and finalizes the accumulated text through the FULL render pipeline (mermaid pass included), so a still-throttled tail chunk never leaves the bubble partial. Idempotent. |
| `__clear()` | none | Empties the transcript. |

Notes:
- `__appendDelta` / `__appendReasoningDelta` / `__setAssistantText` identify
  the bubble by `mid`; a missing bubble is created on demand, so ordering is
  fault-tolerant.
- **Streaming renders markdown block by block** (TUI parity): completed
  blocks render immediately in their own elements (render cost scales with
  the new blocks), only the trailing in-progress block stays raw under the
  cursor, and whole repaints ride a ~5 renders/s throttle so a fast token
  stream cannot flood the host. `__stopStream` / `__setAssistantText` remain
  the authoritative final renders that replace the whole body. A finalized
  bubble (class `stream-done`) never re-opens: late deltas are dropped.
- `window.__linkClick(event)` also exists on `window`, but it is the page's
  internal click interceptor (exposed for the automated test) — hosts do not
  call it.

### Page → host

The page calls host-provided globals (define them before or after load; the
page tolerates them being absent, e.g. in a plain browser):

| Global | Meaning |
|---|---|
| `__javaReport(message: string)` | Progress/diagnostics channel. The page reports: `page-ready` on load (authoritative readiness signal — hosts flush queued renders on it), render confirmations (`user bubble rendered: …`, `assistant bubble rendered (N chars, meta=…)`, `notice rendered: …`, `history rendered (N entries)`, `inbox row rendered (N queued)`, `theme set: …`, `mermaid initialised`, `mermaid blocks found: …`, `mermaid diagram rendered`, `code copied (N chars)` / `copy unavailable (N chars)` — lengths only, never code content), and failures: `JS ERROR in <fn>: <message>` from guarded bridge calls, `JS ERROR: …` from `window.onerror`, `JS REJECTION: …` from unhandled promise rejections, plus `KaTeX failed: …`, `mermaid … FAILED`, `highlight failed (…)` and `external link (no Java bridge): <url>`. |
| `__javaOpenExternal(url: string)` | A non-hash link was clicked. The page never navigates itself (that would destroy the transcript); the host must open the URL externally (OS browser). |
| `__javaForkAt(messageId: string)` | A message's hover **⑂ Fork here** button was clicked; the host forks the session at that server message id. |
| `__javaInboxAction(action: "steer"\|"queue"\|"cancel", messageId: string)` | A queued prompt's **Steer now** / **Deliver next** / **Cancel** button was clicked. The host applies the verb server-side (`steer` = deliver now interrupting the active run, `queue` = deliver after it, `cancel` = remove) and re-pushes the inbox list; on failure the item stays and the host shows a notice. |
| `__javaFileQuery(query: string)` | The @-autocomplete row wants the matches for `query` (fired when the host opens it via `__setFileQuery`). The host runs the server-side file search AND fetches the reference catalog (cached after the first read), fuzzy-filters both, caps the lists and answers with `__setFileCompletions`. |
| `__javaFilePick(value: string)` | A completion row was clicked — a file PATH or an alias reference NAME; the host replaces the `@`-token in its composer input with `@value ` (the reference stays plain text in the message — the server/model resolves it). |
| `__javaFormReply(formId: string, answersJson: string)` | A question-form card's **Submit** button was clicked (U-014). `answersJson` is the answer map keyed by the form's own field keys (`{<fieldKey>: string \| number \| boolean \| string[]}`); the host POSTs it through the session's form-reply verb and re-reads the forms — on failure the card stays and the host shows a notice. |
| `__javaFormCancel(formId: string)` | A question-form card's **Cancel** button was clicked (U-014); the host cancels the form server-side and re-reads the forms. |

## Rendering rules (these rules ARE the contract)

- **Block-level progressive markdown while streaming.** `__appendDelta`
  accumulates the text per `mid`; the page splits it into markdown blocks
  (blank-line separated, fence and `$$` aware). A block that is certainly
  finished — sealed by a later separator, a closed code fence or a
  well-formed table — renders as markdown immediately as its own element and
  is never touched again; only the trailing in-progress block stays raw text
  with the blinking cursor. Repaints are throttled (~5 renders/s, leading
  edge immediate, trailing chunks coalesced); structural commits paint on
  the spot (cost scales with the new blocks). The live pass skips the
  mermaid diagram pass (incomplete source must not error on every tick);
  `__stopStream` and `__setAssistantText` always run the full pipeline and
  are authoritative — including the guard that an EMPTY final reply keeps
  text that already streamed. The `stream-done` class closes a bubble for
  good. Thinking is surfaced during generation: a "thinking…" indicator from
  `__startAssistant` until the first content chunk, then streamed reasoning
  (`__appendReasoningDelta`) in the collapsible details.
- **Math is extracted before markdown.** `$…$`, `$$…$$`, `\(…\)` and `\[…\]`
  spans are replaced by private-use markers, markdown runs on the remainder,
  and KaTeX HTML (`renderToString`, `throwOnError: false`) is re-inserted
  afterwards. This survives shapes that a "markdown first, KaTeX auto-render"
  pipeline destroys: multi-line `$$` blocks (no stray `<br>`), `\(\)`/`\[\]`
  delimiters (markdown would eat the backslashes) and LaTeX `\\` line breaks.
  Currency (`$5 and $10`) is not math; fenced code and inline code are never
  touched by the math pass. Broken TeX degrades to a visible
  `<code class="error">` element, never silence.
- **Mermaid** fences (` ```mermaid `) are left for a diagram pass: the
  `<pre><code class="language-mermaid">` block is replaced by a `div.mermaid`
  and rendered via `mermaid.run({nodes:[…]})`. The bundled mermaid is an
  esbuild IIFE; the API is resolved through a `mermaidApi()` fallback chain:
  `window.mermaid` → `globalThis.mermaid` →
  `globalThis.__esbuild_esm_mermaid.default`. If no API is found the diagram
  source stays visible in an error element (and is reported) instead of an
  empty gap.
- **Syntax highlighting** via highlight.js for fenced code (unknown/untagged
  languages still get code styling without token colours; the `cmake` language
  registers from its own file). The light/dark highlight.js stylesheets are
  toggled by `__setTheme`.
- **XSS hardening:** markdown-it runs with `html: false` (raw HTML in model
  output is escaped, not interpreted); mermaid runs with
  `securityLevel: "strict"`; all interpolations into markup go through HTML
  escaping.
- **Single scrolling Container:** `html, body { overflow: hidden }` — only
  `#chat` scrolls, so an embedded view never shows a double scrollbar.
- **Links never navigate the page** — every non-`#` link click is intercepted
  and handed to `__javaOpenExternal`.
- **Copy-code:** every rendered (non-mermaid) code fence carries a Copy button
  (top-right, theme-aware) that copies the RAW code text via
  `navigator.clipboard` with an `execCommand`/textarea fallback and a brief
  "Copied" confirmation; the exposed `window.__copyCode(button)` hook drives
  the same path in tests. Reports go through `__javaReport` with lengths only.
- **Composer queue row (session inbox):** prompts parked in the server-side
  session inbox render below the transcript with Steer now / Deliver next /
  Cancel per item. The row shows exactly what the host pushed via
  `__setInboxItems` (the host re-reads the server after every action, so a
  failed action keeps the item); button clicks hand `(action, messageId)` to
  `__javaInboxAction`. Prompt text is `textContent`-only — never markup.
- **@-autocomplete (files + alias roots):** the host (whose composer input
  owns the caret) opens the row with `__setFileQuery`, the page fetches the
  proposals through `__javaFileQuery`, and `__setFileCompletions` renders
  them — ALIAS reference roots above the FILES (group headers separate the
  two; the `selected` row is the host's keyboard highlight and spans both
  groups). Clicks hand the picked value to `__javaFilePick` (a path or an
  alias name); the host replaces the `@`-token with `@value ` as plain
  text — nothing is ever injected into the message content. Rows render via
  `textContent`-only.
- **Question forms (U-014):** the forms the session's run raised render as
  answerable cards below the transcript (outside `#chat`, so transcript
  wipes never eat them) and persist until replied/cancelled — no silent
  hang. Fields render leniently from the service's union schema; Submit
  builds the answer map (`{<fieldKey>: value}`, string | number | boolean |
  string[] per field type) and hands it to `__javaFormReply`, Cancel hands
  the form id to `__javaFormCancel`; the card disarms on click (no double
  submit) and the host re-pushes the authoritative list after every action
  (a failed action keeps the card). All content is `textContent`-only.

## Running the checks

Requires Node.js on PATH (only for the checks — the component itself is
build-free):

```
node renderer-check.mjs   # assets present; markdown-it/KaTeX/hljs really render (58 checks)
node bridge-check.mjs     # executes chat.js in a VM with a DOM shim (move-semantics
                          # appendChild, innerHTML serialization of appended trees)
                          # and drives the bridge exactly as a host would
                          # (194 checks, incl. tool lines, copy-code, block-level
                          # progressive streaming, the thinking indicator, the
                          # stream-done stop path, the session-inbox queue row,
                          # the @-alias autocomplete groups and the U-014 form cards)
node mermaid-check.mjs    # renders real diagrams in headless Edge (8 checks;
                          # SKIPs when Edge/puppeteer-core are absent)
```

or `npm run check` for all three. Exit code 0 = all checks pass.

`bridge-check.mjs` honours a `WEB_DIR` environment variable to run against an
alternative copy of the page (used to prove the checks fail when the bridge is
broken).

## Consuming the component

- Serve `web/` over any static file mechanism (the Eclipse consumer runs a
  tiny local HTTP server against a directory resolver; a jar resource
  extraction, WebView `loadFile`/`loadDataWithBaseURL`, or a CDN copy all work
  equally well — relative asset paths are all below `web/`).
- Provide `__javaReport` and `__javaOpenExternal` in the page context.
- Wait for the `page-ready` report, then drive the UI with the calls above.
