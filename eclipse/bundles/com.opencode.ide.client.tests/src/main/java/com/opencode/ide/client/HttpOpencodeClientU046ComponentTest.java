package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.IntegrationInfo;
import com.opencode.ide.client.model.MigrationStatus;
import com.opencode.ide.client.model.SavedPermission;
import com.opencode.ide.client.model.ShellExecutable;
import com.opencode.ide.client.model.SkillInfo;

/**
 * Component test for the U-046 v2 client verbs (slice 1: skills under their
 * list name, shell config, v1 migration status, saved permissions, typed
 * integrations, session generate/view; slice 2: the attach-skill POST):
 * real {@code HttpOpencodeClient} over real HTTP against a local stub
 * server, verifying request paths, methods, bodies and parsing. No Eclipse,
 * no opencode. The stub harness is the shared {@link StubHttpComponentTest}
 * base; only the routing lives here.
 *
 * <p>Stub bodies follow the shapes live-probed against opencode v2.0.19 -
 * notably {@code GET /api/config/shell} answers a BARE array (no
 * {@code {location, data}} envelope), {@code POST /api/session/:id/view}
 * answers 2xx with no content, and the experimental attach-skill POST
 * answers 204 no-content.</p>
 */
public class HttpOpencodeClientU046ComponentTest extends StubHttpComponentTest {

    @BeforeClass
    public static void startStub() throws IOException {
        startStubServer(HttpOpencodeClientU046ComponentTest::respond);
    }

    private static String respond(String path) {
        return switch (path) {
            // v2.0.19 live probe: data items carry the FULL skill body (id,
            // location, content, ...) - SkillInfo models the display fields
            case "/api/skill" -> """
                    {"location":{"directory":"C:/repo"},"data":[
                      {"id":"cpp-tools","name":"cpp-tools","description":"C++ execution utility",
                       "location":"<built-in>","content":"…"}]}
                    """;
            // v2.0.19 live probe: a BARE array, no {location, data} envelope
            // (text block: \\\\ is the JSON-escaped Windows separator)
            case "/api/config/shell" -> """
                    [{"path":"/usr/bin/pwsh","name":"pwsh","acceptable":true},
                     {"path":"C:\\\\Windows\\\\System32\\\\cmd.exe","name":"cmd","acceptable":false}]
                    """;
            // v2.0.19 live probe: {status: ...} at the root, no data envelope
            case "/api/experimental/migration/v1" ->
                "{\"status\":\"completed\",\"current\":1,\"total\":1}";
            case "/api/permission/saved" -> """
                    {"data":[{"id":"perm_saved_1","action":"bash","pattern":"git status","type":"local"}]}
                    """;
            case "/api/integration" -> """
                    {"location":{},"data":[
                      {"id":"anthropic","name":"Anthropic",
                       "methods":[{"type":"key","names":["ANTHROPIC_API_KEY"]},
                                  {"type":"env","names":["ANTHROPIC_KEY","ANTHROPIC_TOKEN"]}],
                       "connections":[]}]}
                    """;
            case "/api/session/ses_1/generate" -> "{\"data\":{\"text\":\"hi\"}}";
            // the view marker answers 2xx with NO content
            case "/api/session/ses_1/view" -> "";
            // v2.0.19 spec: the attach-skill POST answers 204 no-content
            case "/api/experimental/session/ses_1/skill" -> "";
            default -> "{}";
        };
    }

    // ---------- skills under the list-verb name ----------

    @Test
    public void listSkillsReadsTheSkillEndpointAndParsesTheList() throws Exception {
        List<SkillInfo> skills = client.listSkills();

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/skill", lastPath.get());
        assertNull("unscoped listSkills stays unscoped", lastQuery.get());
        assertEquals(1, skills.size());
        assertEquals("cpp-tools", skills.get(0).name());
        assertEquals("C++ execution utility", skills.get(0).description());
    }

    /**
     * Slice 2: the attach payload needs the skill ID - the full skill body
     * carries it (slice 1 dropped it from the record), so the parse must too.
     */
    @Test
    public void skillsParseTheIdFieldForTheAttachPayload() throws Exception {
        List<SkillInfo> skills = client.listSkills();

        assertEquals("cpp-tools", skills.get(0).id());
        assertEquals("the id is the attach payload, the name only the fallback",
                "cpp-tools", skills.get(0).attachId());
    }

    /** REGRESSION family: auxiliary lists resolve per location in v2 - the scope must use bracket syntax. */
    @Test
    public void listSkillsScopesWithLocationBracketSyntax() throws Exception {
        client.listSkills("C:\\Development\\GitHub\\Hephaestus");
        String query = lastQuery.get();
        assertTrue("skill scope must use bracket syntax, got: " + query,
                query != null && query.startsWith("location%5Bdirectory%5D="));
    }

    // ---------- shell config (bare array) ----------

    @Test
    public void shellConfigParsesTheBareArray() throws Exception {
        List<ShellExecutable> shells = client.getShellConfig();

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/config/shell", lastPath.get());
        assertEquals(2, shells.size());
        assertEquals("/usr/bin/pwsh", shells.get(0).path());
        assertEquals("pwsh", shells.get(0).name());
        assertTrue("an acceptable shell", shells.get(0).acceptable());
        assertEquals("the JSON-escaped Windows separator parses", "C:\\Windows\\System32\\cmd.exe",
                shells.get(1).path());
        assertEquals("cmd", shells.get(1).name());
        assertFalse("an unacceptable shell", shells.get(1).acceptable());
    }

    /** The route is new in v2.0.19 - older builds answer 404, which must read as empty. */
    @Test
    public void shellConfigEmptyOn404ForBuildsWithoutTheRoute() throws Exception {
        statusOverride.set(404);
        assertTrue(client.getShellConfig().isEmpty());
    }

    // ---------- v1 migration status ----------

    @Test
    public void migrationStatusReadsTheRootStatus() throws Exception {
        MigrationStatus status = client.getV1MigrationStatus();

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/experimental/migration/v1", lastPath.get());
        assertEquals("completed", status.status());
    }

    /** A {@code running} answer may carry progress fields - they must be tolerated, not fatal. */
    @Test
    public void migrationStatusToleratesUnknownProgressFields() throws Exception {
        bodyOverride.set("{\"status\":\"running\",\"current\":3,\"total\":10}");
        assertEquals("running", client.getV1MigrationStatus().status());
    }

    // ---------- saved permissions ----------

    @Test
    public void savedPermissionsParseFromTheDataEnvelope() throws Exception {
        List<SavedPermission> saved = client.listSavedPermissions();

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/permission/saved", lastPath.get());
        assertNull("remembered rules are user-global - no location scoping", lastQuery.get());
        assertEquals(1, saved.size());
        assertEquals("perm_saved_1", saved.get(0).id());
    }

    @Test
    public void savedPermissionsPropagateHttpErrors() {
        statusOverride.set(500);
        OpencodeException e = assertThrows(OpencodeException.class, () -> client.listSavedPermissions());
        assertTrue("the error names the HTTP status: " + e.getMessage(), e.getMessage().contains("500"));
    }

    @Test
    public void deleteSavedPermissionDeletesById() throws Exception {
        client.deleteSavedPermission("perm_saved_1");

        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/permission/saved/perm_saved_1", lastPath.get());
    }

    // ---------- typed integrations ----------

    @Test
    public void integrationsParseMethodsAndNames() throws Exception {
        List<IntegrationInfo> integrations = client.listIntegrations();

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/integration", lastPath.get());
        assertNull(lastQuery.get());
        assertEquals(1, integrations.size());
        IntegrationInfo anthropic = integrations.get(0);
        assertEquals("anthropic", anthropic.id());
        assertEquals("Anthropic", anthropic.name());
        assertEquals(2, anthropic.methods().size());
        assertEquals("key", anthropic.methods().get(0).type());
        assertEquals(List.of("ANTHROPIC_API_KEY"), anthropic.methods().get(0).names());
        assertEquals("env", anthropic.methods().get(1).type());
        assertEquals(List.of("ANTHROPIC_KEY", "ANTHROPIC_TOKEN"), anthropic.methods().get(1).names());
    }

    /** Slice 2: the integrations info surface shows the connection count - the probe shape carries an empty array. */
    @Test
    public void integrationsParseTheConnectionCount() throws Exception {
        assertEquals(0, client.listIntegrations().get(0).connections());

        bodyOverride.set("""
                {"location":{},"data":[
                  {"id":"github","name":"GitHub","methods":[],"connections":[{},{}]}]}
                """);
        assertEquals("a non-empty connections array reads as its size", 2,
                client.listIntegrations().get(0).connections());
    }

    /** REGRESSION family: the integration catalog resolves per location in v2. */
    @Test
    public void integrationsScopeWithLocationBracketSyntax() throws Exception {
        client.listIntegrations("C:\\repo with spaces");
        assertEquals("/api/integration", lastPath.get());
        assertEquals("location%5Bdirectory%5D=C%3A%5Crepo%20with%20spaces", lastQuery.get());
    }

    // ---------- session generate / view ----------

    @Test
    public void generatePostsThePromptAndReturnsTheText() throws Exception {
        String text = client.generateOnSession("ses_1", "say hi in one word");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/generate", lastPath.get());
        assertEquals("{\"prompt\":\"say hi in one word\"}", lastBody.get());
        assertEquals("hi", text);
    }

    @Test
    public void viewPostsTheIdleEpochMillisAndToleratesNoContent() throws Exception {
        client.markSessionViewed("ses_1", 1717171717171L);

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/view", lastPath.get());
        assertEquals("{\"idle\":1717171717171}", lastBody.get());
    }

    // ---------- attach skill (experimental, v2.0.19 spec) ----------

    /**
     * Pins the EXPERIMENTAL attach call. Body per the v2.0.19 spec
     * ("Activate skill", operationId {@code v2.session.skill}): the REQUIRED
     * member is {@code skill} (the skill id) - the schema's optional
     * {@code id} is a {@code msg_} message anchor, NOT the skill id - and
     * {@code resume} is null/omitted (the server appends the skill message
     * AND resumes). The route is pinned under the /experimental prefix at
     * the 2.0.19 pin.
     */
    @Test
    public void attachSkillPostsTheSpecBodyToTheExperimentalRoute() throws Exception {
        client.attachSkill("ses_1", "cpp-tools");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/experimental/session/ses_1/skill", lastPath.get());
        assertEquals("{\"skill\":\"cpp-tools\"}", lastBody.get());
    }

    /** resume:false appends the skill message WITHOUT resuming; true keeps the documented default (flag omitted). */
    @Test
    public void attachSkillSendsResumeOnlyWhenSuppressingTheResume() throws Exception {
        client.attachSkill("ses_1", "cpp-tools", false);
        assertEquals("{\"skill\":\"cpp-tools\",\"resume\":false}", lastBody.get());

        client.attachSkill("ses_1", "cpp-tools", true);
        assertEquals("{\"skill\":\"cpp-tools\"}", lastBody.get());
    }
}
