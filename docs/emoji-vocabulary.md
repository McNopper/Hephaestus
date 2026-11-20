# Emoji vocabulary (U-074)

## About this document

- **Kind:** `doc` — the ONE canonical emoji mapping for the harness UI.
- **Read by:** anyone adding a visible control, status text or doc illustration;
  **written by:** maintainers.
- **Related:** U-065 (board card language), U-074 (the consistency ticket),
  `docs/TESTING.md` (the checks), `TicketRow` (the code source of truth for
  status/type/blocked).

One concept = **one** emoji across every surface (board, chat, fleet, docs).
Where code renders it (`TicketRow.statusSymbol` / `typeEmoji` / the blocked
marker), the code wins and this table mirrors it; nothing may introduce a
second emoji for a concept already listed.

| Concept | Emoji | Where it renders |
|---|---|---|
| ready | 🆕 | card status column, status headers, legend, fleet badges |
| not-implemented | ❌ | same |
| in-progress | 🏃 | same |
| in-review | 👀 | same |
| done | ✅ | same |
| paused | ⏸️ | same |
| blocked | 🚫 | card title cell prefix |
| type: bug | 🐞 | card type column |
| type: story | 📖 | card type column |
| type: task | 🛠️ | card type column |
| type: spike | ⚡️ | card type column |
| sort: idle | ⇅ | card column headers |
| sort: ascending | ▲ | card column headers |
| sort: descending | ▼ | card column headers |
| action: edit | ✏️ | chat queue row |
| action: remove | 🗑️ | chat queue row |
| action: fork | 🔀 | chat queue row |
| action: background a session | ⤵ | chat composer button/label (U-064) |
| session goal (chip, dialog, goal notice row) | ◎ | chat status strip chip + goal dialog header + synthetic goal notice (T-149; the TUI's prompt-footer glyph) |
| synthetic notice (non-goal source) | ◈ | chat synthetic notice row (T-149; the TUI's generic notice glyph) |

## Rules

- No emoji where a native image already exists (Eclipse toolbar icons stay).
- Header markers are font-sized text (never images) — nothing renders smaller
  than the glyphs around it (review feedback).
- A concept with no good emoji stays plain text; do not force one.
- New surface + new concept → add the row here in the same change.
