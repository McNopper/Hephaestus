package com.opencode.ide.core.context;

/**
 * Seam for opening the native chat with a preselected model. Implemented by the chat bundle and
 * registered as an OSGi service; other bundles (e.g. ui) call it without a compile dependency on
 * the chat bundle.
 */
public interface ChatLauncher {

  /**
   * Focus the chat view and preselect the given model.
   *
   * @param providerId provider id (e.g. {@code "opencode"})
   * @param modelId model id (e.g. {@code "glm-5.2"})
   */
  void openChat(String providerId, String modelId);
}
