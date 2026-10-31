package com.opencode.ide.ui.internal;

import com.opencode.ide.client.ProviderColors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

/**
 * Builds (and caches) a small colored badge image per provider: a filled circle in a deterministic
 * color (from {@link ProviderColors}) with the provider's initial. Gives each provider a distinct
 * icon at a glance without bundling brand assets (replace with official logos later - see roadmap).
 */
public final class ProviderIcons {

  private static final int SIZE = 16;
  private static final ConcurrentMap<String, Image> CACHE = new ConcurrentHashMap<>();

  /**
   * @return a cached 16×16 badge image for the given provider (never {@code null}).
   */
  public static Image imageFor(String providerId, String providerName) {
    String key =
        (providerId != null && !providerId.isBlank())
            ? providerId
            : (providerName != null && !providerName.isBlank() ? providerName : "?");
    return CACHE.computeIfAbsent(
        key, k -> draw(ProviderColors.rgbFor(k), ProviderColors.initialFor(providerName, k)));
  }

  private static Image draw(int rgb, char initial) {
    Display display = Display.getDefault();
    Image image = new Image(display, SIZE, SIZE);
    GC gc = new GC(image);
    Font font = new Font(display, "Segoe UI", 9, SWT.BOLD);
    try {
      gc.setAntialias(SWT.ON);
      Color background = new Color(display, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
      gc.setBackground(background);
      gc.fillOval(1, 1, SIZE - 2, SIZE - 2);
      gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
      gc.setFont(font);
      String letter = String.valueOf(initial);
      Point extent = gc.textExtent(letter);
      gc.drawText(letter, (SIZE - extent.x) / 2, (SIZE - extent.y) / 2, SWT.DRAW_TRANSPARENT);
      background.dispose();
    } finally {
      gc.dispose();
      font.dispose();
    }
    return image;
  }

  /** Dispose all cached images (call on bundle stop). */
  public static void disposeAll() {
    CACHE.values().forEach(Image::dispose);
    CACHE.clear();
  }

  private ProviderIcons() {}
}
