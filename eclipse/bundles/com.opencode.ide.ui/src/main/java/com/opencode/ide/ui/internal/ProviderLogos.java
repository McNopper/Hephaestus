package com.opencode.ide.ui.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.plugin.AbstractUIPlugin;

/**
 * Vendored provider logos (bundled under {@code icons/providers/}, retrieved
 * from Artificial Analysis — provenance and trademark notes in the repo root
 * {@code THIRD-PARTY.md}, section "Provider logos"). Maps the provider ids
 * opencode actually reports onto the bundled logo files; ids without a logo
 * (or whose image fails to load) yield {@code null} so call sites fall back to
 * the {@link ProviderIcons} letter badge.
 */
public final class ProviderLogos {

    private static final String BASE = "icons/providers/";
    private static final String SUFFIX = "_16.png";

    /** opencode provider id (lowercase) → vendored logo slug. */
    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("anthropic", "anthropic"),
            Map.entry("openai", "openai"),
            Map.entry("google", "google"),
            Map.entry("deepseek", "deepseek"),
            Map.entry("meta", "meta"),
            Map.entry("microsoft", "microsoft"),
            Map.entry("nvidia", "nvidia"),
            Map.entry("alibaba", "alibaba"),
            Map.entry("alibabacloud", "alibaba"),
            Map.entry("alibaba-cloud", "alibaba"),
            Map.entry("qwen", "alibaba"),
            Map.entry("amazon", "aws"),
            Map.entry("amazon-bedrock", "aws"),
            Map.entry("aws", "aws"),
            Map.entry("bedrock", "aws"),
            Map.entry("cohere", "cohere"),
            Map.entry("zai", "zai"),
            Map.entry("z-ai", "zai"),
            Map.entry("glm", "zai"),
            Map.entry("bytedance", "bytedance"),
            Map.entry("doubao", "bytedance"),
            Map.entry("baidu", "baidu"),
            Map.entry("minimax", "minimax"),
            Map.entry("openrouter", "openrouter"),
            Map.entry("github", "github"),
            Map.entry("copilot", "github"),
            Map.entry("github-copilot", "github"),
            Map.entry("kimi", "kimi"),
            Map.entry("kimi-coding-plan", "kimi"),
            Map.entry("kimi-for-coding", "kimi"),
            Map.entry("moonshot", "kimi"),
            Map.entry("moonshotai", "kimi"),
            Map.entry("moonshot-ai", "kimi"));

    /** Logo slugs vendored under {@code icons/providers/} (exact-match fallback). {@code kimi} is original artwork, not an Artificial Analysis asset. */
    private static final Set<String> SLUGS = Set.of(
            "alibaba", "anthropic", "aws", "baidu", "bytedance", "cohere", "deepseek",
            "github", "google", "kimi", "meta", "microsoft", "minimax", "nvidia", "openai",
            "openrouter", "zai");

    private static final ConcurrentMap<String, Image> CACHE = new ConcurrentHashMap<>();

    /** @return whether a vendored logo exists for the given provider id. */
    public static boolean hasLogo(String providerId) {
        return descriptorFor(providerId) != null;
    }

    /** @return the logo {@link ImageDescriptor} for the provider id, or {@code null} if none is vendored. */
    public static ImageDescriptor descriptorFor(String providerId) {
        String slug = slugFor(providerId);
        return slug == null ? null
                : AbstractUIPlugin.imageDescriptorFromPlugin(UiActivator.PLUGIN_ID, BASE + slug + SUFFIX);
    }

    /**
     * @return a cached logo {@link Image} for the provider id, or {@code null}
     *         when no logo is vendored or the image cannot be loaded (caller
     *         falls back to the letter badge).
     */
    public static Image imageFor(String providerId) {
        String slug = slugFor(providerId);
        if (slug == null) {
            return null;
        }
        String path = BASE + slug + SUFFIX;
        Image cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(UiActivator.PLUGIN_ID, path);
            if (descriptor == null) {
                return null;
            }
            Image image = descriptor.createImage();
            Image raced = CACHE.putIfAbsent(path, image);
            if (raced != null) {
                image.dispose();
                return raced;
            }
            return image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String slugFor(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        String id = providerId.trim().toLowerCase(Locale.ROOT);
        String hinted = HINTS.get(id);
        if (hinted != null) {
            return hinted;
        }
        return SLUGS.contains(id) ? id : null;
    }

    /** Dispose all cached images (call on bundle stop). */
    public static void disposeAll() {
        CACHE.values().forEach(Image::dispose);
        CACHE.clear();
    }

    private ProviderLogos() {
    }
}
