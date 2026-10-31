package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.board.fleet.FleetJobHandle;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link FleetJobsModel}: add/update/remove, copy-on-read snapshots, and listener
 * notification.
 */
public class FleetJobsModelTest {

  private FleetJobsModel model;
  private final AtomicInteger notifications = new AtomicInteger();

  @Before
  public void setUp() {
    model = new FleetJobsModel();
    notifications.set(0);
    model.addListener(notifications::incrementAndGet);
  }

  private static FleetJobHandle handle(String taskId, FleetJobHandle.State state, String detail) {
    return new FleetJobHandle(taskId, "sess-" + taskId, "C:/wt/" + taskId, state, detail);
  }

  @Test
  public void addAppearsInSnapshotAndNotifies() {
    model.add(handle("T-1", FleetJobHandle.State.RUNNING, null));
    List<FleetJobHandle> jobs = model.jobs();
    assertEquals(1, jobs.size());
    assertEquals("T-1", jobs.get(0).taskId());
    assertEquals(FleetJobHandle.State.RUNNING, jobs.get(0).state());
    assertEquals(1, notifications.get());
  }

  @Test
  public void updateReplacesRowAndNotifies() {
    model.add(handle("T-1", FleetJobHandle.State.RUNNING, null));
    model.update(handle("T-1", FleetJobHandle.State.MERGED, null));
    List<FleetJobHandle> jobs = model.jobs();
    assertEquals(1, jobs.size());
    assertEquals(FleetJobHandle.State.MERGED, jobs.get(0).state());
    assertEquals(2, notifications.get());
  }

  @Test
  public void removeDeletesRowAndNotifiesOnlyOnChange() {
    model.add(handle("T-1", FleetJobHandle.State.FAILED, "boom"));
    model.remove("T-1");
    assertTrue(model.jobs().isEmpty());
    model.remove("T-1");
    assertEquals(2, notifications.get());
  }

  @Test
  public void snapshotIsImmutableCopyOnRead() {
    model.add(handle("T-1", FleetJobHandle.State.RUNNING, null));
    List<FleetJobHandle> snapshot = model.jobs();
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.add(handle("T-2", FleetJobHandle.State.RUNNING, null)));
    assertEquals(1, model.jobs().size());
  }

  @Test
  public void nullHandleIsIgnored() {
    model.add(null);
    assertTrue(model.jobs().isEmpty());
    assertEquals(0, notifications.get());
  }

  @Test
  public void defaultInstanceIsShared() {
    assertSame(FleetJobsModel.getDefault(), FleetJobsModel.getDefault());
  }
}
