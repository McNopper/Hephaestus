package com.opencode.ide.client;

import java.nio.file.Path;

/**
 * One file/image attached to a prompt (chat parity CP-01): the attachment the v2 prompt body's
 * {@code files} field carries. The wire shape is v2's {@code PromptInput.FileAttachment} - {@code
 * uri} (required; an absolute {@code file:} or inline {@code data:} URL) plus optional {@code
 * name}, {@code description}, {@code source} and {@code mention}; only {@code uri} and {@code name}
 * are produced here, which the schema's {@code additionalProperties:false} accepts.
 *
 * <p>Local files become absolute {@code file:} URIs: the opencode server reads each attachment
 * itself before admitting the prompt (20 MiB decoded per item; unsupported binaries are skipped
 * server-side), so the harness never ships file content through the prompt body.
 *
 * @param uri the attachment URI ({@code file:} for a local file the server can read)
 * @param name the plain file name shown to the model and in the transcript
 */
public record ChatAttachment(String uri, String name) {

  /** Attachment for a local file: its absolute {@code file:} URI plus the plain file name. */
  public static ChatAttachment of(Path path) {
    String fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
    return new ChatAttachment(path.toUri().toString(), fileName);
  }
}
