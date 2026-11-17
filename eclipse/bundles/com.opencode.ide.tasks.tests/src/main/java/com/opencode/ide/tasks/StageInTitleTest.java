package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * T-175 (owner directive 2026-11-17) - the store encodes the stage in the ticket NAME. Every write
 * point routes through {@link TaskStore#encodeStageTag}: staged create (birth tag), title update (a
 * rename re-states the tag, never drops it), stage update (the tag follows the Board owner-override
 * move; clearing strips it). The invariants: idempotent (a known V-stage tag is stripped before
 * re-adding - never doubled), tag-exclusive (only {@link VStages} tags are recognized - a caller's
 * own bracket survives), stageless = no V-stage tag, and a name that is nothing but its tag is
 * never emptied. Store + tool surface, over the shared JSON-RPC fixture.
 */
public class StageInTitleTest extends ToolRpcHarness {

  private TaskStore store;

  @Before
  public void createStore() {
    store = new TaskStore(root);
  }

  // ---------- AC1: the three write points ----------

  @Test
  public void aStagedCreateTagsTheTitle() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Fix the widget"), "design");
    assertEquals("[design] Fix the widget", t.title);
    assertEquals(
        "and the tag survives the files",
        "[design] Fix the widget",
        new TaskStore(root).get("p", t.id).title);
  }

  @Test
  public void aStagelessCreateIsNeverTagged() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Plain legacy work"));
    assertEquals("Plain legacy work", t.title);
    // a V-stage bracket handed in for a STAGELESS ticket is stripped - stageless = no tag
    Task stripped = store.create("p", TaskStore.CreateSpec.of("[system] handed in"));
    assertEquals("handed in", stripped.title);
  }

  @Test
  public void aRenameReStatesTheCurrentStageTag() {
    Task t = store.create("p", TaskStore.CreateSpec.of("First"), "implementation");
    store.update("p", t.id, Map.of("title", "Second"));
    assertEquals("[implementation] Second", store.get("p", t.id).title);
    // renaming with the tag already in the new title must not double it
    store.update("p", t.id, Map.of("title", "[implementation] Second"));
    assertEquals("[implementation] Second", store.get("p", t.id).title);
  }

  @Test
  public void theTagFollowsAStageMoveAndStripsOnClear() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Moving target"), "design");
    // the Board owner-override path: store.update with "stage" (the actor verb rejects it)
    store.update("p", t.id, Map.of("stage", "test-system"));
    assertEquals("[test-system] Moving target", store.get("p", t.id).title);
    store.update("p", t.id, Map.of("stage", "design"));
    assertEquals("[design] Moving target", store.get("p", t.id).title);
    // Map.of refuses null - a clearing stage write goes through a mutable map
    Map<String, Object> clear = new LinkedHashMap<>();
    clear.put("stage", null);
    store.update("p", t.id, clear);
    assertEquals("Moving target", store.get("p", t.id).title);
  }

  @Test
  public void titleAndStageInOneCallEndOnTheNewStagesTag() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Combined"), "design");
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("title", "Renamed");
    changes.put("stage", "architecture");
    store.update("p", t.id, changes);
    assertEquals("[architecture] Renamed", store.get("p", t.id).title);
  }

  // ---------- AC1 invariants: idempotent, tag-exclusive, never-empty ----------

  @Test
  public void encodingIsIdempotentAndTagExclusive() {
    assertEquals("[design] x", TaskStore.encodeStageTag("[design] x", "design"));
    assertEquals(
        "no double tag on a re-encode",
        "[design] x",
        TaskStore.encodeStageTag("[design] x", "design"));
    assertEquals(
        "a caller's own bracket survives right of the stage tag",
        "[design] [WIP] draft",
        TaskStore.encodeStageTag("[WIP] draft", "design"));
    assertEquals(
        "a tag naming ANOTHER stage is replaced, not stacked",
        "[system] x",
        TaskStore.encodeStageTag("[design] x", "system"));
    assertEquals(
        "never empties a name that is nothing but its tag (stageless)",
        "[design]",
        TaskStore.encodeStageTag("[design]", null));
    assertEquals(
        "a bare tag with a stage restates the stage",
        "[design]",
        TaskStore.encodeStageTag("[system]", "design"));
    assertEquals("a null title stays null", null, TaskStore.encodeStageTag(null, "design"));
  }

  // ---------- the tool surface (chat-first: the verb returns the tagged name) ----------

  @Test
  public void theToolSurfaceReturnsTheTaggedTitle() {
    JsonObject created =
        callOk(
            "task_create",
            "{\"project\":\"p\",\"title\":\"Via the verb\",\"stage\":\"test-design\"}");
    assertEquals("[test-design] Via the verb", created.get("title").getAsString());

    JsonObject fetched =
        callOk(
            "task_get",
            "{\"project\":\"p\",\"ticket_id\":\"" + created.get("id").getAsString() + "\"}");
    assertEquals("[test-design] Via the verb", fetched.get("title").getAsString());
  }

  // ---------- the engine's writers keep the invariant ----------

  @Test
  public void engineStyleWritesKeepTheTag() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Ticked"), "implementation");
    // ticker-style writes: status flips + artifacts never lose the name tag
    store.update("p", t.id, Map.of("status", "in-review"));
    assertEquals("[implementation] Ticked", store.get("p", t.id).title);
    store.addArtifact("p", t.id, "file", "docs/x.md", "product", "tester");
    assertEquals("[implementation] Ticked", store.get("p", t.id).title);
  }

  @Test
  public void aTaggedRenameIsBookkeepingNotAnInputChange() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Stamped"), "implementation");
    store.update("p", t.id, Map.of("status", "in-review"));
    var stampAfterStructuredWrite = store.get("p", t.id).inputsChangedAt;
    // T-175: the title write re-states the tag but stays bookkeeping - D-003 option B
    store.update("p", t.id, Map.of("title", "Stamped again"));
    assertEquals("[implementation] Stamped again", store.get("p", t.id).title);
    assertFalse(
        "a title (tag) write never bumps the inputs stamp",
        store.get("p", t.id).inputsChangedAt.isAfter(stampAfterStructuredWrite));
  }

  @Test
  public void existingTaggedFilesRoundTrip() {
    Task t = store.create("p", TaskStore.CreateSpec.of("Persist me"), "test-system");
    Task back = new TaskStore(root).get("p", t.id);
    assertEquals("[test-system] Persist me", back.title);
    assertTrue(
        "the codec writes the tagged title raw-stably",
        TaskFileCodec.write(back).contains("title: [test-system] Persist me"));
  }

  private JsonObject callOk(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertFalse("expected success: " + result, result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }
}
