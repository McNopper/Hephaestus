package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.OauthStart;

/**
 * Component test for the U-048 client-verbs slice (the integration connect
 * flows command/key/oauth with their attempt poll/complete/abort legs, the
 * full-replace session environment, and the experimental session import):
 * real {@code HttpOpencodeClient} over real HTTP against a local stub
 * server, verifying request paths, methods, exact bodies and parsing.
 * No Eclipse, no opencode. The stub harness is the shared
 * {@link StubHttpComponentTest} base; only the routing lives here.
 *
 * <p>Route shapes probed against the live opencode v2.0.19 OpenAPI: the
 * integration connect routes live at the ROOT {@code /api} prefix (no
 * {@code /experimental}), the attempt answers come back as lenient maps
 * (the {@code Integration.Attempt*} bodies stay unmodelled), and the import
 * route is experimental. The U-048 refactor pin at the bottom keeps
 * {@code beginProviderOauth} riding the generic OAuth verb unchanged.</p>
 */
public class HttpOpencodeClientU048ComponentTest extends StubHttpComponentTest {

    @BeforeClass
    public static void startStub() throws IOException {
        startStubServer(HttpOpencodeClientU048ComponentTest::respond);
    }

    private static String respond(String path) {
        return switch (path) {
            // the catalog beginProviderOauth picks the OAuth method from
            case "/api/integration" -> """
                    {"location":{},"data":[
                      {"id":"anthropic","name":"Anthropic",
                       "methods":[{"type":"key"},{"id":"browser","type":"oauth","label":"Anthropic Console"}],
                       "connections":[]}]}
                    """;
            // the started command attempt
            case "/api/integration/anthropic/connect/command" -> """
                    {"location":{},"data":{"attemptID":"att_cmd_1",
                      "command":"opencode auth login","status":"pending"}}
                    """;
            // a key connect answers the created connection
            case "/api/integration/anthropic/connect/key" -> """
                    {"location":{},"data":{"id":"conn_key_1","integrationID":"anthropic",
                      "type":"key","label":"Work"}}
                    """;
            // the started OAuth attempt - the shape beginProviderOauth reads
            case "/api/integration/anthropic/connect/oauth" -> """
                    {"location":{},"data":{"attemptID":"att_oa_1",
                      "url":"https://auth.anthropic.com/oauth","instructions":"open the url",
                      "mode":"auto","time":{"created":1,"expires":2}}}
                    """;
            // the command attempt's poll status (the DELETE abort hits this
            // path too - its answer body is ignored)
            case "/api/integration/anthropic/connect/command/att_cmd_1" ->
                "{\"location\":{},\"data\":{\"attemptID\":\"att_cmd_1\",\"status\":\"expired\"}}";
            // the OAuth attempt's poll status
            case "/api/integration/anthropic/connect/oauth/att_oa_1" ->
                "{\"location\":{},\"data\":{\"attemptID\":\"att_oa_1\",\"status\":\"completed\",\"connectionID\":\"conn_oa_1\"}}";
            // completing the OAuth attempt answers the connection
            case "/api/integration/anthropic/connect/oauth/att_oa_1/complete" ->
                "{\"location\":{},\"data\":{\"id\":\"conn_oa_1\",\"integrationID\":\"anthropic\",\"type\":\"oauth\"}}";
            // the environment PUT answers 2xx with no content
            case "/api/session/ses_1/environment" -> "";
            // the import answers the imported session
            case "/api/experimental/session/import" ->
                "{\"location\":{},\"data\":{\"id\":\"ses_imported\",\"title\":\"Imported session\"}}";
            default -> "{}";
        };
    }

    // ---------- connect starts (command / key / oauth) ----------

    @Test
    public void startIntegrationCommandPostsTheMethodIdAndParsesTheAttempt() throws Exception {
        Map<String, Object> attempt = client.startIntegrationCommand("anthropic", "default", "Work laptop");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/command", lastPath.get());
        assertEquals("{\"methodID\":\"default\",\"label\":\"Work laptop\"}", lastBody.get());
        assertEquals("att_cmd_1", attempt.get("attemptID"));
        assertEquals("pending", attempt.get("status"));
    }

    @Test
    public void startIntegrationKeyPostsTheKeyAndParsesTheConnection() throws Exception {
        Map<String, Object> connection = client.startIntegrationKey("anthropic", "sk-ant-key", "Work");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/key", lastPath.get());
        assertEquals("{\"key\":\"sk-ant-key\",\"label\":\"Work\"}", lastBody.get());
        assertEquals("conn_key_1", connection.get("id"));
        assertEquals("key", connection.get("type"));
    }

    @Test
    public void startIntegrationOauthPostsTheMethodIdAndParsesTheAttempt() throws Exception {
        Map<String, Object> attempt = client.startIntegrationOauth("anthropic", "browser", "Anthropic");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth", lastPath.get());
        assertEquals("{\"methodID\":\"browser\",\"label\":\"Anthropic\"}", lastBody.get());
        assertEquals("att_oa_1", attempt.get("attemptID"));
        assertEquals("https://auth.anthropic.com/oauth", attempt.get("url"));
    }

    /** The optional label is OMITTED from every connect body when null. */
    @Test
    public void connectBodiesOmitTheOptionalLabelWhenNull() throws Exception {
        client.startIntegrationCommand("anthropic", "default", null);
        assertEquals("{\"methodID\":\"default\"}", lastBody.get());

        client.startIntegrationKey("anthropic", "sk-ant-key", null);
        assertEquals("{\"key\":\"sk-ant-key\"}", lastBody.get());

        client.startIntegrationOauth("anthropic", "browser", null);
        assertEquals("{\"methodID\":\"browser\"}", lastBody.get());
    }

    // ---------- attempt polling ----------

    @Test
    public void commandAttemptPollsTheAttemptStatus() throws Exception {
        Map<String, Object> status = client.integrationCommandAttempt("anthropic", "att_cmd_1");

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/command/att_cmd_1", lastPath.get());
        assertEquals("att_cmd_1", status.get("attemptID"));
        assertEquals("expired", status.get("status"));
    }

    @Test
    public void oauthAttemptPollsTheAttemptStatus() throws Exception {
        Map<String, Object> status = client.integrationOauthAttempt("anthropic", "att_oa_1");

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth/att_oa_1", lastPath.get());
        assertEquals("completed", status.get("status"));
        assertEquals("conn_oa_1", status.get("connectionID"));
    }

    /** Lenient parse: an attempt status may also arrive as a BARE object, without the data envelope. */
    @Test
    public void attemptStatusParsesBareObjectsToo() throws Exception {
        bodyOverride.set("{\"attemptID\":\"att_cmd_1\",\"status\":\"done\"}");

        assertEquals("done", client.integrationCommandAttempt("anthropic", "att_cmd_1").get("status"));
    }

    // ---------- OAuth completion ----------

    @Test
    public void completeIntegrationOauthPostsTheCodeAndParsesTheConnection() throws Exception {
        Map<String, Object> connection = client.completeIntegrationOauth("anthropic", "att_oa_1", "cb-code-123");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth/att_oa_1/complete", lastPath.get());
        assertEquals("{\"code\":\"cb-code-123\"}", lastBody.get());
        assertEquals("conn_oa_1", connection.get("id"));
    }

    /** The schema marks code OPTIONAL: a null code completes with an EMPTY object. */
    @Test
    public void completeIntegrationOauthSendsAnEmptyObjectWithoutACode() throws Exception {
        client.completeIntegrationOauth("anthropic", "att_oa_1", null);

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth/att_oa_1/complete", lastPath.get());
        assertEquals("{}", lastBody.get());
    }

    // ---------- attempt aborts ----------

    @Test
    public void abortCommandAttemptDeletesAndToleratesAlreadyGone() throws Exception {
        client.abortIntegrationCommandAttempt("anthropic", "att_cmd_1");
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/command/att_cmd_1", lastPath.get());

        // 404/409 = the attempt is already gone (expired, completed or
        // aborted elsewhere) - the outcome the caller wanted, not a failure
        statusOverride.set(404);
        client.abortIntegrationCommandAttempt("anthropic", "att_cmd_1");
        statusOverride.set(409);
        client.abortIntegrationCommandAttempt("anthropic", "att_cmd_1");

        statusOverride.set(500);
        assertThrows(OpencodeException.class,
                () -> client.abortIntegrationCommandAttempt("anthropic", "att_cmd_1"));
    }

    @Test
    public void abortOauthAttemptDeletesAndToleratesAlreadyGone() throws Exception {
        client.abortIntegrationOauthAttempt("anthropic", "att_oa_1");
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth/att_oa_1", lastPath.get());

        statusOverride.set(404);
        client.abortIntegrationOauthAttempt("anthropic", "att_oa_1");
        statusOverride.set(409);
        client.abortIntegrationOauthAttempt("anthropic", "att_oa_1");

        statusOverride.set(500);
        assertThrows(OpencodeException.class,
                () -> client.abortIntegrationOauthAttempt("anthropic", "att_oa_1"));
    }

    // ---------- session environment (full-replace, no GET on the wire) ----------

    @Test
    public void replaceSessionEnvironmentPutsTheVariables() throws Exception {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("ANTHROPIC_API_KEY", "sk-1");
        variables.put("CI", "true");

        client.replaceSessionEnvironment("ses_1", variables);

        assertEquals("PUT", lastMethod.get());
        assertEquals("/api/session/ses_1/environment", lastPath.get());
        assertEquals("{\"variables\":{\"ANTHROPIC_API_KEY\":\"sk-1\",\"CI\":\"true\"}}", lastBody.get());
    }

    /** A null map clears the environment: the required variables member is sent EMPTY. */
    @Test
    public void replaceSessionEnvironmentSendsEmptyVariablesForNull() throws Exception {
        client.replaceSessionEnvironment("ses_1", null);

        assertEquals("PUT", lastMethod.get());
        assertEquals("/api/session/ses_1/environment", lastPath.get());
        assertEquals("{\"variables\":{}}", lastBody.get());
    }

    // ---------- session import (experimental) ----------

    @Test
    public void importSessionPostsInfoMessagesAndLocation() throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "Imported session");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("text", "hello");

        Map<String, Object> imported = client.importSession(info, List.of(message), "C:\\repo");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/experimental/session/import", lastPath.get());
        assertEquals("{\"info\":{\"title\":\"Imported session\"}"
                + ",\"messages\":[{\"role\":\"user\",\"text\":\"hello\"}]"
                + ",\"location\":{\"directory\":\"C:\\\\repo\"}}", lastBody.get());
        assertEquals("ses_imported", imported.get("id"));
    }

    /** The optional location is OMITTED when the directory is null (createSession's location-body pattern). */
    @Test
    public void importSessionOmitsTheLocationWhenNull() throws Exception {
        client.importSession(Map.of("title", "x"), List.of(), null);

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/experimental/session/import", lastPath.get());
        assertEquals("{\"info\":{\"title\":\"x\"},\"messages\":[]}", lastBody.get());
    }

    // ---------- U-048 refactor pin: the provider-OAuth convenience rides the generic verb ----------

    /**
     * U-048 refactor pin: {@code beginProviderOauth} delegates to the generic
     * {@code startIntegrationOauth} - same route, same {@code {methodID}}
     * body (label omitted), same lenient answer mapping. The pre-existing
     * convenience must keep working unchanged next to the new verbs (the
     * H5b/H5c suites carry its full behavioral matrix).
     */
    @Test
    public void beginProviderOauthRidesTheGenericOauthVerb() throws Exception {
        OauthStart started = client.beginProviderOauth("anthropic");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth", lastPath.get());
        assertEquals("{\"methodID\":\"browser\"}", lastBody.get());
        assertEquals("https://auth.anthropic.com/oauth", started.url());
        assertEquals("auto", started.method());
        assertEquals("open the url", started.instructions());
    }
}
