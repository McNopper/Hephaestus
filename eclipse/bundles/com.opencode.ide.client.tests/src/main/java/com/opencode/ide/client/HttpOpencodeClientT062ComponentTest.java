package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.SavedPermission;
import java.io.IOException;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Contract + logic tests for CP-07 (FR-CP-07, T-062): the saved-permission EDIT route ({@code PATCH
 * /api/permission/saved/{id}} - live-openapi-probed body {@code {action, resource}} and the
 * 409-on-duplicate rule), the list's new {@code action}/{@code resource} fields, the pure
 * always-rule matcher behind AC3's verification, and the ask row's input line (AC1's display half;
 * the event-to-input parsing itself lives in PermissionEvents).
 */
public class HttpOpencodeClientT062ComponentTest extends StubHttpComponentTest {

  @BeforeClass
  public static void startStub() throws IOException {
    startStubServer(HttpOpencodeClientT062ComponentTest::respond);
  }

  private static String respond(String path) {
    return switch (path) {
      case "/api/permission/saved" ->
          "{\"location\":{},\"data\":[{\"id\":\"perm_saved_1\",\"action\":\"allow\","
              + "\"resource\":\"/src/*\",\"pattern\":\"/src/*\"}]}";
      default -> "";
    };
  }

  // ---------- AC2: the EDIT route ----------

  @Test
  public void listSavedPermissionsCarriesTheActionAndResource() throws OpencodeException {
    List<SavedPermission> rules = client.listSavedPermissions();

    assertEquals("/api/permission/saved", lastPath.get());
    assertEquals(1, rules.size());
    assertEquals("perm_saved_1", rules.get(0).id());
    assertEquals("allow", rules.get(0).action());
    assertEquals("/src/*", rules.get(0).resource());
  }

  @Test
  public void updateSavedPermissionPatchesActionAndResource() throws OpencodeException {
    bodyOverride.set(
        "{\"data\":{\"id\":\"perm_saved_1\",\"action\":\"deny\",\"resource\":\"/etc/*\"}}");

    SavedPermission updated = client.updateSavedPermission("perm_saved_1", "deny", "/etc/*");

    assertEquals("PATCH", lastMethod.get());
    assertEquals("/api/permission/saved/perm_saved_1", lastPath.get());
    assertEquals("{\"action\":\"deny\",\"resource\":\"/etc/*\"}", lastBody.get());
    assertEquals("perm_saved_1", updated.id());
    assertEquals("/etc/*", updated.resource());
  }

  /** The live schema's 409 (the pair already exists) must be loud, never a silent success. */
  @Test
  public void aConflictOnTheSamePairIsLoudNotSilent() {
    statusOverride.set(409);
    bodyOverride.set("{\"error\":\"conflict\"}");

    OpencodeException failure =
        assertThrows(
            OpencodeException.class,
            () -> client.updateSavedPermission("perm_saved_1", "allow", "/src/*"));

    assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP 409"));
  }

  // ---------- AC3: the always-rule matcher ----------

  @Test
  public void aSavedRuleMatchesTheRequestedPatternExactlyAndByWildcard() {
    SavedPermission wildcard = new SavedPermission("perm_saved_1", "allow", "/src/*");
    assertTrue(wildcard.matches(List.of("/src/anything")));
    assertTrue(wildcard.matches(List.of("/src/*")));
    assertFalse(wildcard.matches(List.of("/etc/passwd")));

    // a requested wildcard is covered by the narrower stored rule too
    assertTrue(new SavedPermission("perm_2", "allow", "/etc/passwd").matches(List.of("/etc/*")));
    assertFalse(new SavedPermission("perm_3", "allow", "/etc/passwd").matches(List.of("/src/*")));
  }

  @Test
  public void absentFieldsNeverMatch() {
    assertFalse(new SavedPermission("perm_4").matches(List.of("/src/*")));
    assertFalse(new SavedPermission("perm_5", "allow", null).matches(List.of("/src/*")));
    assertFalse(new SavedPermission("perm_6", "allow", "/src/*").matches(List.of()));
    assertFalse(new SavedPermission("perm_7", "allow", "/src/*").matches(null));
    assertFalse(new SavedPermission("perm_8", "allow", "  ").matches(List.of("/src/*")));
  }

  // ---------- AC1: the ask row's input line ----------

  @Test
  public void theAskInputLineCarriesTheMetadataInput() {
    PermissionRequest request =
        new PermissionRequest(
            "ses_1",
            "per_1",
            "bash",
            List.of("git push"),
            "git push",
            PermissionRequest.Status.PENDING,
            "{\"command\":\"git push\"}");

    assertEquals("{\"command\":\"git push\"}", request.inputDisplay());
  }

  @Test
  public void aHugeInputTruncatesAndAnAbsentInputRendersNothing() {
    PermissionRequest huge =
        new PermissionRequest(
            "ses_1",
            "per_2",
            "edit",
            List.of(),
            null,
            PermissionRequest.Status.PENDING,
            "y".repeat(500));
    assertEquals("truncated with the ellipsis marker", 401, huge.inputDisplay().length());

    // the pre-CP-07 six-arg shape (no input) renders no line at all
    PermissionRequest legacy =
        new PermissionRequest(
            "ses_1", "per_3", "edit", List.of(), null, PermissionRequest.Status.PENDING);
    assertEquals(null, legacy.inputDisplay());
  }
}
