package com.opencode.ide.client;

/**
 * Deterministic color + initial derivation for a provider, so each provider gets
 * a stable, distinguishable badge icon (colored circle with its initial). Pure -
 * no UI dependencies - so it can be unit-tested, and the SWT side just draws it.
 */
public final class ProviderColors {

    private ProviderColors() {
    }

    /** Deterministic hue in [0,360) for a provider id (0 for null/blank). */
    public static float hueFor(String id) {
        if (id == null || id.isBlank()) {
            return 0f;
        }
        int hash = id.hashCode();
        return Math.floorMod(hash, 360);
    }

    /** RGB int (0xRRGGBB) at a fixed saturation/lightness for visual consistency. */
    public static int rgbFor(String id) {
        return hslToRgb(hueFor(id), 0.55f, 0.50f);
    }

    /** The uppercase first character of the name, falling back to the id, then '?'. */
    public static char initialFor(String name, String id) {
        String source = (name != null && !name.isBlank()) ? name : (id != null && !id.isBlank() ? id : "?");
        return Character.toUpperCase(source.charAt(0));
    }

    /** Standard HSL→RGB (h in degrees, s/l in [0,1]) → 0xRRGGBB. */
    public static int hslToRgb(float hue, float saturation, float lightness) {
        float h = ((hue % 360) + 360) % 360;
        float c = (1 - Math.abs(2 * lightness - 1)) * saturation;
        float x = c * (1 - Math.abs(((h / 60f) % 2) - 1));
        float m = lightness - c / 2;
        float r;
        float g;
        float b;
        if (h < 60) {
            r = c;
            g = x;
            b = 0;
        } else if (h < 120) {
            r = x;
            g = c;
            b = 0;
        } else if (h < 180) {
            r = 0;
            g = c;
            b = x;
        } else if (h < 240) {
            r = 0;
            g = x;
            b = c;
        } else if (h < 300) {
            r = x;
            g = 0;
            b = c;
        } else {
            r = c;
            g = 0;
            b = x;
        }
        int ri = Math.round((r + m) * 255);
        int gi = Math.round((g + m) * 255);
        int bi = Math.round((b + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }
}
