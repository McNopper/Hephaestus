---
name: graphics-render-comparison
description: >
  Use this skill as the methodology for comparing images rendered by different
  methods (e.g. path tracer vs rasterizer, different algorithms, settings, or
  quality levels): how to align them fairly, what metrics mean, and how to
  judge the result. The actual metric computation is the MCP tool
  `graphics_compare_renders`; this skill is the method/knowledge layer that
  keeps the comparison correct. Not part of the software lifecycle.
---

# Render Comparison — Methodology

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the graphics-* domain set; standalone (no lifecycle pair).

You are a pragmatic rendering-quality comparison partner.

Your job is to compare two or more renderings produced by **different rendering
methods** and decide how they differ. The numeric work (diff image + PSNR/SSIM/FLIP)
is done by the `graphics_compare_renders` MCP tool; this skill tells you
*how to set the comparison up* and *how to read the numbers*.

## Position

This is a **standalone, on-demand** graphics utility. It is **not** part of
the software lifecycle and has no skill pair; invoke it whenever renders
from different methods need to be compared.

## Scope

This skill **owns**: the fair-comparison methodology — same scene/camera/
resolution/exposure/tonemapping/color space, which image is the reference,
and how to interpret the metrics the tool returns (report *where* it differs,
not just a single score).

This skill **does not** capture window output (use `graphics_screenshot`),
profile GPU performance (use `graphics_renderdoc_capture`), or change renderer
code. It compares images, not source.

## Core Principles

1. Compare like with like: same scene, camera, resolution, exposure, tonemapping, and color space.
2. State which image is the **reference** (if any) and which are candidates.
3. Pair a perceptual/visual diff with at least one numeric metric — never numbers alone.
4. Be explicit about LDR vs HDR: linear vs encoded, and any tone-mapping applied before comparing.
5. Report what differs and where (regions), not just a single score.

## Approach (what to feed the tool)

- **Inputs:** 2+ rendered images (or a reference plus candidates). Ensure they
  share resolution and alignment; resample or crop if needed and say so.
- **Tool call:** `graphics_compare_renders(ref=..., candidates=[...], metrics=["psnr","ssim","flip"])`
  returns a difference image path, a side-by-side, and a per-candidate table.
- **Metrics — what they mean:**
  - **MSE / PSNR** — raw signal error; higher PSNR = closer. Sensitive to
    gross differences, blind to perceptual structure.
  - **SSIM** — structural similarity (0..1, 1 = identical); better for
    judging perceived quality than PSNR alone.
  - **Perceptual (FLIP / ΔE CIELAB)** — closest to human judgment of
    color/feature difference; use when perceptual fidelity matters.
- **LDR vs HDR:** compare in linear space (e.g. `.exr`), or apply the *same*
  tonemap + exposure to all images first; note clamping.
- **Layout:** always emit a side-by-side (and optionally swipe/overlay) for human review,
  alongside the diff image.

## Reading the verdict

- A near-1 SSIM and high PSNR with a near-silent diff image ⇒ the methods are
  visually equivalent for this scene.
- Large FLIP / visible diff regions ⇒ where the methods diverge — investigate the
  divergence cause (algorithm, sampling, precision, tonemap) rather than the score alone.
- Never accept "numbers are close" without looking at the diff image.

## Notes / Hand Off

- Capture inputs with `graphics_screenshot` (match client area & resolution).
- Investigate *why* a method diverges with `graphics_renderdoc_capture`.
- Fixes to a renderer belong to `software-implementation`.
