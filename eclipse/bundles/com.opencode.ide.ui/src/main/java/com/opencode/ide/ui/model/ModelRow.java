package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.Model;

/**
 * One row of the Providers table: a model paired with its provider name/id and whether it is that
 * provider's default. SWT-free — the Providers view and its filter/sort/labels operate on this
 * record only.
 */
public record ModelRow(String providerName, String providerId, Model model, boolean defaultModel) {}
