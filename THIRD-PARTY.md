# Third-Party Licenses

This file aggregates the licenses and copyright notices of the third-party
libraries **redistributed with** the Hephaestus template: runtime dependencies
of its bundled MCP servers, vendored web assets shipped inside the Eclipse chat
plugin, and vendored trademark logo assets. Hephaestus itself is
distributed under the **MIT License** — see [`LICENSE`](LICENSE).

All listed dependencies are permissive and fully compatible with the MIT
project license.

> The license texts and copyright holders below are the canonical SPDX texts with
> the best-known copyright holders. The authoritative source for each is the
> upstream package; regenerate this file at install time with
> `pip-licenses --from=mixed --with-license-file --format=markdown` against the `mcp/*/requirements.txt` files if you want a verified, environment-specific copy.

---

## Pillow (Python) — version ≥10.0

- **License:** HPND (Historical Permission Notice and Disclaimer)
- **Upstream:** https://python-pillow.org / https://github.com/python-pillow/Pillow
- **Used by:** `mcp/graphics`
- **Copyright:**
  - Copyright © 2010 by Jeffrey A. Crystal and contributors
  - Copyright © 1997-2011 by Secret Labs AB
  - Copyright © 1995-2011 by Fredrik Lundh

Permission to use, copy, modify, and distribute this software and its
associated documentation for any purpose and without fee is hereby granted,
provided that the above copyright notice appears in all copies, and that both
that copyright notice and this permission notice appear in supporting
documentation, and that the name of Secret Labs AB or the author not be used in
advertising or publicity pertaining to distribution of the software without
specific, written prior permission.

SECRET LABS AB AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO
EVENT SHALL SECRET LABS AB OR THE AUTHOR BE LIABLE FOR ANY SPECIAL, INDIRECT OR
CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS
ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS
SOFTWARE.

---

## NumPy (Python) — version ≥1.24

- **License:** BSD-3-Clause
- **Upstream:** https://numpy.org / https://github.com/numpy/numpy
- **Used by:** `mcp/graphics`
- **Copyright:** Copyright (c) 2005-2024, NumPy Developers. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
* Neither the name of the NumPy Developers nor the names of its contributors may
  be used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

## Provider logos (trademark assets, vendored)

- **What:** low-resolution provider identification logos used by the Eclipse
  Providers view (`eclipse/bundles/com.opencode.ide.ui`, see
  `icons/providers/` and `ProviderLogos.java`).
- **Source:** Artificial Analysis, URL pattern
  `https://artificialanalysis.ai/img/logos/<slug>_small.svg`.
- **Retrieved:** 2026-08-18 (via `eclipse/bundles/com.opencode.ide.ui/fetch-logos.ps1`,
  which also rasterizes each SVG to `<slug>_16.png` / `<slug>_32.png` with cairosvg).
- **License status:** the logos are **trademarks of their respective owners**
  (see table below) and are **NOT covered by this repository's MIT license**
  (see [`LICENSE`](LICENSE)). They are vendored offline at low resolution solely
  to identify the corresponding AI provider in the UI (nominative fair use);
  no endorsement is implied. If you are a trademark owner and object to this
  use, open an issue and the asset will be removed.

| File(s) under `icons/providers/` | Provider | Trademark owner |
|---|---|---|
| `alibaba.*` | Alibaba (Qwen) | Alibaba Group Holding Limited |
| `anthropic.*` | Anthropic | Anthropic PBC |
| `aws.*` | Amazon Web Services | Amazon.com, Inc. |
| `baidu.*` | Baidu (ERNIE) | Baidu, Inc. |
| `bytedance.*` | ByteDance (Doubao) | ByteDance, Ltd. |
| `cohere.*` | Cohere | Cohere, Inc. |
| `deepseek.*` | DeepSeek | Hangzhou DeepSeek Artificial Intelligence Basic Technology Research Co., Ltd. |
| `github.*` | GitHub Copilot | GitHub, Inc. |
| `google.*` | Google (Gemini) | Google LLC |
| `kimi.*` | Kimi (Moonshot AI) | none — original artwork, see note below |
| `meta.*` | Meta (Llama) | Meta Platforms, Inc. |
| `microsoft.*` | Microsoft (Azure) | Microsoft Corporation |
| `minimax.*` | MiniMax | Shanghai MiniMax Artificial Intelligence Co., Ltd. |
| `nvidia.*` | NVIDIA (NIM) | NVIDIA Corporation |
| `openai.*` | OpenAI | OpenAI, LLC (OpenAI OpCo, LLC) |
| `openrouter.*` | OpenRouter | OpenRouter Inc. |
| `zai.*` | Z.AI (GLM) | Zhipu AI Inc. (Z.AI) |

Artificial Analysis itself is © V7 Labs (artificialanalysis.ai); the hosting
site claims no ownership of the provider logos listed above.

**Exception — `kimi.*`:** Artificial Analysis ships no Moonshot AI / Kimi logo
(all slug spellings 404 as of 2026-09-16), so `icons/providers/svg/kimi.svg`
and the rasterized `kimi_16.png`/`kimi_32.png` are **original artwork drawn
for this repository** (a generic crescent-moon mark) and ARE covered by the
repository's MIT license. `fetch-logos.ps1` deliberately excludes `kimi` from
its Ship list so a refresh never overwrites it.

---

## Vendored web assets (Eclipse chat renderer — `eclipse/components/chat-web/web/`)

The chat view's embedded renderer vendors four minified JS/CSS libraries; the
Eclipse chat bundle copies them into its plugin jar at build time
(`copy-chat-web-assets` in `eclipse/bundles/com.opencode.ide.chat/pom.xml`),
so they are redistributed with the deployed plugin.

| Library | License | Upstream | Vendored files |
|---|---|---|---|
| markdown-it | MIT | https://github.com/markdown-it/markdown-it | `markdown-it.min.js` |
| mermaid | MIT | https://github.com/mermaid-js/mermaid | `mermaid.min.js` |
| KaTeX | MIT | https://github.com/KaTeX/KaTeX | `katex/katex.min.js`, `katex/katex.min.css`, `katex/fonts/*` (~60 font files) |
| highlight.js | BSD-3-Clause | https://github.com/highlightjs/highlight.js | `hljs/highlight.min.js`, `hljs/cmake.min.js`, `hljs/github.min.css`, `hljs/github-dark.min.css` |

**markdown-it** — Copyright (c) 2014 Vitaly Puzrin, Andrei Kuncin

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

**mermaid** — Copyright (c) 2014-2026 Knut Sveidqvist and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

**KaTeX** — Copyright (c) 2013-2026 Khan Academy Inc. and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The bundled KaTeX font files are part of KaTeX and carry its MIT notice; see
https://github.com/KaTeX/KaTeX/tree/main/fonts for per-font attribution.

**highlight.js** — Copyright (c) 2006-2026 Ivan Sagalaev and contributors

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
* Neither the name of the copyright holder nor the names of its contributors may
  be used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

## Not redistributed (no attribution obligation for this template)

The following third-party components are **not** redistributed with Hephaestus,
so their full license texts are not aggregated here:

- **@opencode-ai/plugin** (Node, MIT) — the local opencode plugin. `.opencode/package.json`
  and `node_modules/` are **git-ignored** (see `.opencode/.gitignore`); it is a per-clone
  convenience, not part of the template.
- **GoogleTest** (C++, BSD-3-Clause, v1.17.0) — fetched on demand by the `cpp/` template via
  CMake `FetchContent` when `ENABLE_TESTING=ON`. Consumers fetch it themselves; it is never
  checked into this repository.
- **Doxygen** (build tool) — invoked via `find_package` when `ENABLE_DOXYGEN=ON`; a system
  documentation tool, not linked or redistributed.
