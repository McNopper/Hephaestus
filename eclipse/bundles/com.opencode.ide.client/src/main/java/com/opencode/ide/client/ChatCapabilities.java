package com.opencode.ide.client;

/**
 * The system prompt that tells the model what the Eclipse chat view can render.
 *
 * <p>Sent as the {@code system} field of {@code POST /session/:id/message}, so it applies to
 * <em>every</em> message without touching the user's opencode configuration and without polluting
 * the visible transcript.
 *
 * <p>Why not a skill or an agent: opencode <em>skills</em> are invoked by the model on demand when
 * a task matches, so they cannot guarantee output formatting; an <em>agent</em> prompt would work
 * but requires writing files into the user's opencode config and would override the agent they
 * picked (build/plan/…). A per-request system prompt is the mechanism the server provides for
 * exactly this - client capability advertisement.
 */
public final class ChatCapabilities {

  private ChatCapabilities() {}

  /** Kept short on purpose: it is prepended to every request and costs tokens. */
  public static final String RENDERER_SYSTEM_PROMPT =
      """
            You are answering inside the Eclipse IDE chat panel of the opencode plugin, \
            not a terminal. The panel renders your reply as GitHub-flavored Markdown with:
            - LaTeX math: inline $x^2$ or \\(x^2\\), display $$…$$ or \\[…\\] (KaTeX). \
            Use it for any formula, symbol or unit instead of ASCII art.
            - Fenced code blocks with syntax highlighting. ALWAYS tag the language \
            (```c, ```cpp, ```cmake, ```make, ```bash, ```python, ```java, ```json, ```xml). \
            The user works on C and C++ projects, so prefer ```c / ```cpp for code.
            - Mermaid diagrams in ```mermaid fences: use them for architecture, call \
            flows, state machines and sequences when a diagram explains it better than prose.
            - Tables, task lists, blockquotes and inline `code`.
            Do not use terminal escape codes, box-drawing characters or ASCII tables. \
            Keep answers concise and skimmable.""";
}
