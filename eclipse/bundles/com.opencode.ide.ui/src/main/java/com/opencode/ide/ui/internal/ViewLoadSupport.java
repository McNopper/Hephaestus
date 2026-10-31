package com.opencode.ide.ui.internal;

import java.util.function.Consumer;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

/**
 * Shared async load support for the OpenCode views (Server, Providers): runs a load operation on a
 * system job with retry, then delivers the result or the final failure on the UI thread.
 *
 * <p>Both {@link com.opencode.ide.client.OpencodeException} and unchecked exceptions are retried
 * and finally reported, so a view can no longer get stuck on "Loading..." when a
 * non-OpencodeException escapes the load (the previous per-view loops only caught
 * OpencodeException).
 */
public final class ViewLoadSupport {

  public static final int ATTEMPTS = 3;
  public static final long RETRY_DELAY_MILLIS = 2000L;

  private ViewLoadSupport() {}

  /** The background work: performs the IO and returns the result (or throws). */
  @FunctionalInterface
  public interface Loader<T> {
    T load() throws Exception;
  }

  /** Runs {@code loader} off the UI thread with retries; delivers to the UI thread. */
  public static <T> void load(
      String jobName, Loader<T> loader, Consumer<T> onSuccess, Consumer<Throwable> onError) {
    Job job =
        Job.create(
            jobName,
            monitor -> {
              Throwable last = null;
              for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                  T result = loader.load();
                  asyncExec(() -> onSuccess.accept(result));
                  return Status.OK_STATUS;
                } catch (Exception e) {
                  last = e;
                  if (attempt + 1 < ATTEMPTS && !pause()) {
                    break;
                  }
                }
              }
              Throwable error =
                  (last != null)
                      ? last
                      : new IllegalStateException(jobName + " failed without an exception");
              asyncExec(() -> onError.accept(error));
              return Status.OK_STATUS;
            });
    job.setSystem(true);
    job.schedule();
  }

  private static boolean pause() {
    try {
      Thread.sleep(RETRY_DELAY_MILLIS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void asyncExec(Runnable runnable) {
    Display display = Display.getDefault();
    if (display != null && !display.isDisposed()) {
      display.asyncExec(runnable);
    }
  }

  public static String message(Throwable t) {
    if (t == null) {
      return "unknown error";
    }
    String message = t.getMessage();
    return (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message;
  }
}
