package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionGoal;
import java.io.IOException;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Component test for the T-149 status-strip client verbs: the single-session read (session cost for
 * the strip), the goal-state RPC (chip + dialog, tolerant toward plugin-less servers), the
 * fire-and-forget goal command (pause/resume/clear - deliberately NOT {@code runCommand}, whose
 * reply poll would wait PROMPT_TIMEOUT 60 min for an assistant reply that never comes: the probed
 * contract answers 204 with no body and no reply) and the model catalog read feeding the
 * context-window percent ({@code limit.context}). Real {@code HttpOpencodeClient} over real HTTP
 * against the shared {@link StubHttpComponentTest} stub - only the routing and the assertions live
 * here. Route/body shapes probed live against the local opencode 2.3.0 service 2026-11-16.
 */
public class HttpOpencodeClientT149ComponentTest extends StubHttpComponentTest {

  @BeforeClass
  public static void startStub() throws IOException {
    startStubServer(HttpOpencodeClientT149ComponentTest::respond);
  }

  private static String respond(String path) {
    return switch (path) {
      // GET /api/session/{sid}: v2 wraps the resource under {data}
      case "/api/session/ses_t1" ->
          """
          {"location":{},"data":{"id":"ses_t1","title":"T-149","cost":9.96,
           "tokens":{"input":700,"output":42,"reasoning":7,
                     "cache":{"read":11,"write":3}}}}
          """;
      // POST /api/rpc/opencode.goal/get: the Goal.State rides under {output}
      case "/api/rpc/opencode.goal/get" ->
          """
          {"output":{"id":"g-1","objective":"Ship it","status":"active",
           "reason":"started","rounds":1,"maxRounds":10}}
          """;
      // POST /api/session/{sid}/command: the goal command answers 204, no body
      // (empty respond body = 2xx no-content in the harness; status set per test)
      case "/api/session/ses_t1/command" -> "";
      // GET /api/model: the flat catalog with limit.context under {data}
      case "/api/model" ->
          """
          {"location":{},"data":[{"id":"m1","modelID":"m1","providerID":"prov",
           "limit":{"context":1048576,"output":64000}}]}
          """;
      default -> "";
    };
  }

  @Test
  public void getSessionReadsCostAndTokensUnderTheDataEnvelope() throws OpencodeException {
    Session session = client.getSession("ses_t1");

    assertEquals("GET", lastMethod.get());
    assertEquals("/api/session/ses_t1", lastPath.get());
    assertEquals("ses_t1", session.id());
    assertEquals(9.96, session.cost(), 0.000001);
    assertEquals(700L, session.tokens().input());
    assertEquals(7L, session.tokens().reasoning());
    assertEquals(3L, session.tokens().cache().write());
  }

  @Test
  public void getSessionGoalPostsTheInputAndReadsTheOutput() throws OpencodeException {
    SessionGoal goal = client.getSessionGoal("ses_t1");

    assertEquals("POST", lastMethod.get());
    assertEquals("/api/rpc/opencode.goal/get", lastPath.get());
    assertEquals("{\"input\":{\"sessionID\":\"ses_t1\"}}", lastBody.get());
    assertEquals("g-1", goal.id());
    assertEquals("Ship it", goal.objective());
    assertEquals("active", goal.status());
    assertEquals("started", goal.reason());
  }

  /** A server without the goal plugin answers an error - the contract is "no goal", not a throw. */
  @Test
  public void getSessionGoalToleratesAnErrorAnswerAsNoGoal() throws OpencodeException {
    statusOverride.set(500);
    bodyOverride.set("{\"error\":\"method not found\"}");

    assertNull("an error answer means no goal, never a throw", client.getSessionGoal("ses_t1"));
  }

  /** Malformed or null-output answers degrade the same way (the TUI's catch-to-undefined). */
  @Test
  public void getSessionGoalToleratesMalformedAndNullOutputAsNoGoal() throws OpencodeException {
    bodyOverride.set("not json at all");
    assertNull("a malformed body means no goal", client.getSessionGoal("ses_t1"));

    bodyOverride.set("{\"output\":null}");
    assertNull("a null output is a session without a goal", client.getSessionGoal("ses_t1"));
  }

  /**
   * The probed goal-command contract: {@code {name, text}} against the command route, answered
   * 204/no-content - this call returns at once. Had it ridden {@code runCommand}, the reply poll
   * would hang the test here for PROMPT_TIMEOUT.
   */
  @Test
  public void postSessionCommandPostsFireAndForgetAndNeverPolls() throws OpencodeException {
    statusOverride.set(204);

    client.postSessionCommand("ses_t1", "goal", "pause");

    assertEquals("POST", lastMethod.get());
    assertEquals("/api/session/ses_t1/command", lastPath.get());
    assertEquals("{\"name\":\"goal\",\"text\":\"pause\"}", lastBody.get());
  }

  @Test
  public void getModelsListsTheCatalogWithContextLimits() throws OpencodeException {
    List<Model> models = client.getModels("C:\\repo");

    assertEquals("GET", lastMethod.get());
    assertEquals("/api/model", lastPath.get());
    assertEquals(1, models.size());
    assertEquals("prov", models.get(0).providerID());
    assertEquals("m1", models.get(0).id());
    assertEquals(1_048_576L, models.get(0).limit().context());
  }
}
