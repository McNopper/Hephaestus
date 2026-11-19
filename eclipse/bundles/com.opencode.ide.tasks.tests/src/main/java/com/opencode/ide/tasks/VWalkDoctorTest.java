package com.opencode.ide.tasks;

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The deterministic V-walk check (owner model 2026-11-19): the left leg divides with at least one
 * link to the NEXT stage, every walked left-leg ticket is joined on the right leg by a test-*
 * ticket whose basis is exactly it (1:1 join-back), right-leg joins point at LEFT-leg tickets only,
 * and every basis/epic link resolves in the live pool OR the archive pool. Counts converge as a
 * walk completes: |left| = |right| under these rules. Shipped legacy work is exempt through a
 * recorded "division SATISFIED" comment (SKILL stage-pipeline: division-with-rationale) - never
 * mint duplicate children for shipped work.
 */
public class VWalkDoctorTest extends StoreTestHarness {

  private Task create(String title, String stage, String basis) {
    return store.create(
        "p",
        new TaskStore.CreateSpec(
            title,
            "d",
            "task",
            "developer",
            "high",
            3,
            List.of(),
            List.of(),
            null,
            "T",
            null,
            null,
            basis,
            null),
        stage);
  }

  private void shipDone(Task t) {
    store.addArtifact("p", t.id, "doc", "docs/" + t.stage + "-product.md", "stage product", "f");
    store.update("p", t.id, Map.of("status", "done"));
  }

  private static boolean mentions(List<String> out, String id, String needle) {
    return out.stream().anyMatch(s -> s.startsWith(id + ":") && s.contains(needle));
  }

  private static boolean quietFor(List<String> out, String id) {
    return out.stream().noneMatch(s -> s.startsWith(id + ":"));
  }

  @Test
  public void doneLeftLegMustDivideForwardAndBeJoinedOnTheRightLeg() {
    Task req = create("requirement", "requirements", null);
    shipDone(req);
    List<String> drift = store.inconsistencies("p");
    assertTrue(
        "I1 flags the missing next-stage link: " + drift,
        mentions(drift, req.id, "has no link to the next stage (system)"));
    assertTrue(
        "I3 flags the missing right-leg join: " + drift,
        mentions(drift, req.id, "not joined on the right leg"));

    create("system child", "system", req.id); // I1 satisfied
    create("acceptance join", "test-requirements", req.id); // I3 satisfied
    assertTrue(
        "a divided + joined left-leg ticket is quiet: " + store.inconsistencies("p"),
        quietFor(store.inconsistencies("p"), req.id));
  }

  @Test
  public void legacyShippedWorkIsExemptThroughTheSatisfiedComment() {
    Task req = create("legacy requirement", "requirements", null);
    shipDone(req);
    store.addComment("p", req.id, "division SATISFIED (legacy: shipped pre-model)", "po");
    List<String> drift = store.inconsistencies("p");
    assertTrue("the SATISFIED comment exempts shipped work: " + drift, quietFor(drift, req.id));
  }

  @Test
  public void rightLegJoinsBackToLeftLegTicketsOnly() {
    Task req = create("root", "requirements", null);
    Task rightOk = create("right ok", "test-system", req.id);
    Task rightBad = create("right bad", "test-system", null);
    Task rightWorse = create("right worse", "test-design", rightBad.id);
    store.update("p", rightBad.id, Map.of("status", "in-review"));
    store.update("p", rightWorse.id, Map.of("status", "in-review"));
    List<String> drift = store.inconsistencies("p");
    assertTrue(
        "a right-leg ticket without basis is flagged: " + drift,
        mentions(drift, rightBad.id, "has no basis"));
    assertTrue(
        "a right-leg ticket joining the right leg is flagged: " + drift,
        mentions(drift, rightWorse.id, "joins back to right-leg ticket"));
    assertTrue("the well-formed join stays quiet: " + drift, quietFor(drift, rightOk.id));
    assertTrue("the left-leg root needs no flags of its own", quietFor(drift, req.id));
  }

  @Test
  public void linksResolveIntoTheArchivePool() {
    Task parent = create("archivable", "requirements", null);
    shipDone(parent);
    store.archive("p", parent.id, "wave close");
    Task child = create("reattached child", "system", parent.id);
    assertTrue("the archive join was accepted at create", parent.id.equals(child.basis));
    List<String> drift = store.inconsistencies("p");
    assertTrue(
        "no dangling flag for an archive join: " + drift,
        drift.stream().noneMatch(s -> s.contains("basis " + parent.id + " does not resolve")));
  }
}
