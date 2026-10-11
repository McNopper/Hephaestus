package com.opencode.ide.board.model;

import com.opencode.ide.board.fleet.FleetJobHandle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observable registry of {@link FleetJobHandle} rows shown in the Fleet view, keyed by task id.
 * SWT-free: listeners are plain {@link Runnable}s invoked on the mutating thread (views wrap them
 * in {@code Display.asyncExec}). Snapshots are copy-on-read and immutable.
 */
public final class FleetJobsModel {

  private static final FleetJobsModel DEFAULT = new FleetJobsModel();

  private final Map<String, FleetJobHandle> jobs = new LinkedHashMap<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  /** The process-wide registry shared by the Board view (launch) and Fleet view (display). */
  public static FleetJobsModel getDefault() {
    return DEFAULT;
  }

  /** Adds (or replaces) the row for a task id and notifies listeners. */
  public synchronized void add(FleetJobHandle job) {
    if (job == null) {
      return;
    }
    jobs.put(job.taskId(), job);
    fire();
  }

  /** Same as {@link #add} — named for intent when a row's state/detail changes. */
  public synchronized void update(FleetJobHandle job) {
    add(job);
  }

  /** Removes the row for a task id; notifies only when something was removed. */
  public synchronized void remove(String taskId) {
    if (jobs.remove(taskId) != null) {
      fire();
    }
  }

  /** Immutable copy of the current rows, in insertion order. */
  public synchronized List<FleetJobHandle> jobs() {
    return List.copyOf(jobs.values());
  }

  public void addListener(Runnable listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void fire() {
    for (Runnable listener : listeners) {
      listener.run();
    }
  }
}
