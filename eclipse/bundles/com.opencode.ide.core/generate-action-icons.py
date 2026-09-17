#!/usr/bin/env python3
"""Generates the shared action-icon set for the Eclipse view toolbars.

Every Eclipse view toolbar action used to be text-only, which renders as a
flat label with no button affordance (user feedback 2026-09-16: "at first
sight it is not clear that these are buttons"). The platform's shared images
do not cover this plugin's vocabulary (abort, thinking, fork, dispatch...),
so this script draws a small ORIGINAL line-icon set: 16x16 viewBox, 1.6px
stroke, round caps, theme-neutral mid-gray (#6E6E6E - legible on both the
light and dark Eclipse themes; toolbar icons do not theme-adapt).

The artwork is original and MIT-licensed like the repository itself (see
THIRD-PARTY.md, "Action icons"); unlike the provider logos nothing here is a
third-party trademark.

Output: src/main/resources/icons/actions/svg/<name>.svg plus a rasterized
src/main/resources/icons/actions/<name>.png (16px, transparent) consumed by
the views via AbstractUIPlugin.imageDescriptorFromPlugin("com.opencode.ide.core", ...).

Usage (from this bundle directory):  python generate-action-icons.py
Requires: Python with cairosvg (pip install cairosvg).
"""

import os
import subprocess
import sys

STROKE = "#6E6E6E"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "src", "main", "resources", "icons", "actions")
SVG_DIR = os.path.join(OUT_DIR, "svg")

# name -> list of SVG shape strings (all share stroke/fill defaults unless overridden)
FILLED = f'fill="{STROKE}" stroke="none"'
OPEN = f'fill="none" stroke="{STROKE}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"'

ICONS = {
    # chat view
    "new-session": [
        f'<path d="M4 2h5l3 3v9H4z" {OPEN}/>',
        f'<path d="M9 2v3h3" {OPEN}/>',
        f'<path d="M8 7.8v4.4M5.8 10h4.4" {OPEN}/>',
    ],
    "abort": [
        f'<rect x="3.5" y="3.5" width="9" height="9" rx="1.5" {FILLED}/>',
    ],
    "thinking": [
        f'<path d="M2.5 3h11v7h-6l-3 3v-3h-2z" {OPEN}/>',
        f'<circle cx="5.5" cy="6.5" r="1" {FILLED}/>',
        f'<circle cx="8" cy="6.5" r="1" {FILLED}/>',
        f'<circle cx="10.5" cy="6.5" r="1" {FILLED}/>',
    ],
    # shared
    "refresh": [
        f'<path d="M13.2 8a5.2 5.2 0 1 1-1.5-3.7" {OPEN}/>',
        f'<path d="M13.6 1.6v3h-3" {OPEN}/>',
    ],
    "auto-refresh": [
        f'<path d="M12.4 7.2a4.6 4.6 0 1 1-1.3-3.2" {OPEN}/>',
        f'<path d="M12.8 1.7v2.6h-2.6" {OPEN}/>',
        f'<path d="M10.6 9.6l4 2.7-4 2.7z" {FILLED}/>',
    ],
    "reconnect": [
        f'<path d="M6 2.5v3M10 2.5v3" {OPEN}/>',
        f'<path d="M4.5 5.5h7v2.8a3.5 3.5 0 0 1-7 0z" {OPEN}/>',
        f'<path d="M8 11.8v1.7" {OPEN}/>',
    ],
    "expand-all": [
        f'<path d="M3 3.5l5 5 5-5M3 8.5l5 5 5-5" {OPEN}/>',
    ],
    "collapse-all": [
        f'<path d="M3 12.5l5-5 5 5M3 7.5l5-5 5 5" {OPEN}/>',
    ],
    # session details view
    "fork": [
        f'<circle cx="4" cy="3.5" r="1.5" {OPEN}/>',
        f'<circle cx="12" cy="3.5" r="1.5" {OPEN}/>',
        f'<circle cx="4" cy="12.5" r="1.5" {OPEN}/>',
        f'<path d="M4 5v6M12 5c0 3.2-4.5 2.2-4.5 5" {OPEN}/>',
    ],
    "share": [
        f'<circle cx="12.3" cy="3.7" r="1.7" {OPEN}/>',
        f'<circle cx="3.7" cy="8" r="1.7" {OPEN}/>',
        f'<circle cx="12.3" cy="12.3" r="1.7" {OPEN}/>',
        f'<path d="M5.2 7.1l5.4-2.6M5.2 8.9l5.4 2.6" {OPEN}/>',
    ],
    "summarize": [
        f'<path d="M2.5 4h8M2.5 7h8M2.5 10h4.5M2.5 13h4.5" {OPEN}/>',
        f'<path d="M11.8 8.8l.8 1.7 1.9.3-1.4 1.3.3 1.9-1.6-.9-1.6.9.3-1.9-1.4-1.3 1.9-.3z" {FILLED}/>',
    ],
    # board view
    "blocked-only": [
        f'<path d="M5.7 2.5h4.6l3.2 3.2v4.6l-3.2 3.2H5.7l-3.2-3.2V5.7z" {OPEN}/>',
        f'<path d="M4.8 11.2l6.4-6.4" {OPEN}/>',
    ],
    "bugs-only": [
        f'<ellipse cx="8" cy="9.2" rx="3.1" ry="4" {OPEN}/>',
        f'<path d="M8 5.2V3.2M5.8 3.6h4.4" {OPEN}/>',
        f'<path d="M2.6 8.4h2.3M11.1 8.4h2.3M3.2 12.2l1.8-1M11 11.2l1.8 1M5.2 6L3.8 4.6M10.8 6l1.4-1.4" {OPEN}/>',
    ],
    "stages": [
        f'<path d="M2.5 3h11l-4.4 5.4V13l-2.2-1.6V8.4z" {OPEN}/>',
    ],
    "sync-store": [
        f'<ellipse cx="8" cy="4" rx="5" ry="1.8" {OPEN}/>',
        f'<path d="M3 4v8c0 1 2.2 1.8 5 1.8s5-.8 5-1.8V4" {OPEN}/>',
        f'<path d="M6.6 8.4l-1.8 1.8 1.8 1.8M9.4 8.4l1.8 1.8-1.8 1.8" {OPEN}/>',
    ],
    "cost-overview": [
        f'<circle cx="8" cy="8" r="5.6" {OPEN}/>',
        f'<path d="M10.1 5.9c-.5-.8-1.3-1.2-2.1-1.2-1.4 0-2.4 1-2.4 2.1 0 2.8 4.9 1.6 4.9 3.9 0 1.2-1.1 2.1-2.5 2.1-.8 0-1.7-.5-2.2-1.2" {OPEN}/>',
        f'<path d="M8 3.4v1.3M8 12.6v-1.5" {OPEN}/>',
    ],
    "launch": [
        f'<path d="M4.5 2.8v10.4l8-5.2z" {FILLED}/>',
    ],
    "auto-dispatch": [
        f'<path d="M9 1.8L4.2 9h3L6.4 14.2 12 7H8.8z" {OPEN}/>',
    ],
    "auto-loop": [
        f'<path d="M2.8 6.2A3.8 3.8 0 0 1 6.6 2.8h6.2M13 3.2l-2.2-1.6M13 3.2l-2.2 1.6" {OPEN}/>',
        f'<path d="M13.2 9.8a3.8 3.8 0 0 1-3.8 3.4H3.2M3 12.8l2.2 1.6M3 12.8l2.2-1.6" {OPEN}/>',
    ],
    "dispatch-settings": [
        f'<path d="M2.5 5h11M2.5 8h11M2.5 11h11" {OPEN}/>',
        f'<circle cx="6" cy="5" r="1.7" {FILLED}/>',
        f'<circle cx="10.5" cy="8" r="1.7" {FILLED}/>',
        f'<circle cx="4.8" cy="11" r="1.7" {FILLED}/>',
    ],
    "take-over": [
        f'<circle cx="5" cy="4.8" r="2.1" {OPEN}/>',
        f'<path d="M1.6 13.2c0-2.4 1.5-3.9 3.4-3.9 1 0 1.8.3 2.5.9" {OPEN}/>',
        f'<path d="M10.2 8h4.3M12.8 6.4L15 8l-2.2 1.6" {OPEN}/>',
    ],
    # repo view
    "show-tree": [
        f'<rect x="1.8" y="1.8" width="4.6" height="3.6" rx="1" {OPEN}/>',
        f'<rect x="9.6" y="10.6" width="4.6" height="3.6" rx="1" {OPEN}/>',
        f'<path d="M4.1 5.4V9h5.5v1.6" {OPEN}/>',
    ],
    # fleet view
    "watch": [
        f'<path d="M1.8 8C3.7 4.8 5.8 3.2 8 3.2s4.3 1.6 6.2 4.8c-1.9 3.2-4 4.8-6.2 4.8S3.7 11.2 1.8 8z" {OPEN}/>',
        f'<circle cx="8" cy="8" r="2" {OPEN}/>',
    ],
    "open-diff": [
        f'<rect x="2" y="3" width="12" height="10" rx="1.5" {OPEN}/>',
        f'<path d="M8 3v10M4 6.6h2M5 5.6v2M11 9.4H9" {OPEN}/>',
    ],
    "open-folder": [
        f'<path d="M2 4.5h4l1.6 1.6H14V12a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" {OPEN}/>',
    ],
    "permissions": [
        f'<circle cx="5.5" cy="5.5" r="2.8" {OPEN}/>',
        f'<path d="M7.7 7.7l5.8 5.8M10.2 10.2l1.6-1.6M12.2 12.2l1.4-1.4" {OPEN}/>',
    ],
    "events": [
        f'<path d="M8 2.4a4 4 0 0 1 4 4c0 2.9 1 3.9 1.5 4.4h-11c.5-.5 1.5-1.5 1.5-4.4a4 4 0 0 1 4-4z" {OPEN}/>',
        f'<path d="M6.8 13.3a1.3 1.3 0 0 0 2.4 0" {OPEN}/>',
    ],
}


def svg_document(shapes):
    body = "\n  ".join(shapes)
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">\n'
            f'  {body}\n</svg>\n')


def main():
    os.makedirs(SVG_DIR, exist_ok=True)
    for name, shapes in sorted(ICONS.items()):
        svg_path = os.path.join(SVG_DIR, f"{name}.svg")
        with open(svg_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(svg_document(shapes))
        png_path = os.path.join(OUT_DIR, f"{name}.png")
        subprocess.run(
            [sys.executable, "-m", "cairosvg", svg_path, "-o", png_path,
             "--output-width", "16", "--output-height", "16"],
            check=True)
        print(f"OK  {name}")
    print(f"{len(ICONS)} icons -> {OUT_DIR}")


if __name__ == "__main__":
    main()
