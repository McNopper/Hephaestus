package com.opencode.ide.client.model;

/**
 * Result of {@code POST /session/:id/shell} (opencode v1.18.30): the created
 * assistant message plus its shell tool part, flattened leniently - the
 * message {@code id} and {@code agent} from {@code info}, the executed
 * {@code command}, the run {@code status} and the captured {@code output}
 * from the tool part's {@code state}. All fields may be {@code null} when
 * the server omits them.
 */
public record ShellResult(
        String messageId,
        String agent,
        String command,
        String status,
        String output) {
}
