package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.chat.internal.CommandComposer.CommandSelection;
import com.opencode.ide.chat.internal.CommandComposer.Kind;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.CommandInfo;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the SWT-free slash-command composer: picker trigger rules (leading slash, plain
 * text, whitespace-completed), prefix matching (case-insensitive, name order, capped at 8), the
 * command/message selection mapping, and lenient loading - against a fake connection whose client
 * only implements {@code getCommands()}.
 */
public class CommandComposerTest {

  private FakeConnection connection;
  private CommandComposer composer;

  @Before
  public void setUp() {
    connection = new FakeConnection();
    composer = new CommandComposer(connection);
  }

  private void loaded(CommandInfo... commands) {
    connection.client.commands = Arrays.asList(commands);
    composer.loadCommands();
  }

  // ---------- picker trigger ----------

  @Test
  public void leadingSlashWithoutWhitespaceTriggersThePicker() {
    assertTrue(composer.isPickerTrigger("/"));
    assertTrue(composer.isPickerTrigger("/b"));
    assertTrue(composer.isPickerTrigger("/build"));
  }

  @Test
  public void plainOrNullTextNeverTriggersThePicker() {
    assertFalse(composer.isPickerTrigger(null));
    assertFalse(composer.isPickerTrigger(""));
    assertFalse(composer.isPickerTrigger("   "));
    assertFalse(composer.isPickerTrigger("hello"));
    assertFalse(composer.isPickerTrigger(" /build")); // slash not at position 0
  }

  @Test
  public void whitespaceAfterTheSlashHidesThePicker() {
    assertFalse(composer.isPickerTrigger("/build "));
    assertFalse(composer.isPickerTrigger("/build now"));
    assertFalse(composer.isPickerTrigger("/build\nnow"));
  }

  // ---------- matching ----------

  @Test
  public void matchesFilterByCaseInsensitivePrefix() {
    CommandInfo build = cmd("build", "run a build");
    CommandInfo optimize = cmd("optimize", "tune code");
    loaded(build, optimize);

    // case-insensitivity (the picked convention): "/OPT" still finds optimize
    assertEquals(List.of(build), composer.matches("/b"));
    assertEquals(List.of(optimize), composer.matches("/OPT"));
    assertEquals(List.of(build, optimize), composer.matches("/")); // bare slash proposes all
    assertEquals(List.of(), composer.matches("/zzz"));
  }

  @Test
  public void matchesReturnNameOrderCappedAtEight() {
    CommandInfo[] many = new CommandInfo[12];
    for (int i = 0; i < many.length; i++) {
      many[i] = cmd("cmd" + (char) ('a' + i), null); // cmda .. cmdl
    }
    loaded(many);

    List<CommandInfo> matches = composer.matches("/");
    assertEquals(8, matches.size());
    assertEquals("cmda", matches.get(0).name());
    assertEquals("cmdh", matches.get(7).name());
  }

  @Test
  public void matchesIgnoreTextWithoutALeadingSlash() {
    loaded(cmd("build", null));
    assertEquals(List.of(), composer.matches(null));
    assertEquals(List.of(), composer.matches(""));
    assertEquals(List.of(), composer.matches("build"));
    assertEquals(List.of(), composer.matches("run /build"));
  }

  @Test
  public void matchingToleratesUnsortedListsAndUnusableEntries() {
    connection.client.commands =
        Arrays.asList(
            null,
            cmd("zebra", null),
            new CommandInfo(null, "no name"),
            new CommandInfo("  ", "blank name"),
            cmd("alpha", null));
    composer.loadCommands();

    List<CommandInfo> matches = composer.matches("/");
    assertEquals(2, matches.size());
    assertEquals("alpha", matches.get(0).name()); // name order, not load order
    assertEquals("zebra", matches.get(1).name());
  }

  // ---------- loading leniency ----------

  @Test
  public void loadFailureDegradesToNoCommands() {
    connection.client.failure = new OpencodeException("down");
    composer.loadCommands();

    assertEquals(List.of(), composer.matches("/"));
  }

  @Test
  public void nullCommandListDegradesToNoCommands() {
    connection.client.commands = null;
    composer.loadCommands();

    assertEquals(List.of(), composer.matches("/"));
  }

  @Test
  public void emptyCommandListYieldsNoMatches() {
    composer.loadCommands();

    assertEquals(List.of(), composer.matches("/"));
    assertEquals(List.of(), composer.matches("/x"));
  }

  // ---------- selection ----------

  @Test
  public void selectMapsPickedCommandWithRemainderToArguments() {
    loaded(cmd("build", null));

    CommandSelection selection = composer.select(cmd("build", null), "/build debug build now");
    assertEquals(Kind.COMMAND, selection.kind());
    assertEquals("build", selection.command().name());
    assertEquals(List.of("debug build now"), selection.arguments());
    assertEquals("/build debug build now", selection.message());
  }

  @Test
  public void selectWithoutRemainderSendsNoArguments() {
    CommandSelection selection = composer.select(cmd("build", null), "/build");
    assertEquals(Kind.COMMAND, selection.kind());
    assertEquals(List.of(), selection.arguments());
  }

  @Test
  public void selectMatchesTheCommandNameCaseInsensitively() {
    CommandSelection selection = composer.select(cmd("build", null), "/Build now");
    assertEquals(Kind.COMMAND, selection.kind());
    assertEquals(List.of("now"), selection.arguments());
  }

  @Test
  public void selectWithoutACommandDegradesToAMessage() {
    CommandSelection selection = composer.select(null, "/build now");
    assertEquals(Kind.MESSAGE, selection.kind());
    assertNull(selection.command());
    assertEquals(List.of(), selection.arguments());
    assertEquals("/build now", selection.message());
  }

  @Test
  public void selectWithNullTextIsBlankSafe() {
    CommandSelection selection = composer.select(cmd("build", null), null);
    assertEquals(Kind.COMMAND, selection.kind());
    assertEquals(List.of(), selection.arguments());
    assertNull(selection.message());
  }

  // ---------- resolve (submission without an explicit pick) ----------

  @Test
  public void resolveExactLeadingCommandNameRunsTheCommand() {
    loaded(cmd("build", null), cmd("optimize", null));

    CommandSelection selection = composer.resolve("/optimize the code");
    assertEquals(Kind.COMMAND, selection.kind());
    assertEquals("optimize", selection.command().name());
    assertEquals(List.of("the code"), selection.arguments());
  }

  @Test
  public void resolveMatchesTheLeadingTokenCaseInsensitively() {
    loaded(cmd("build", null));

    assertEquals(Kind.COMMAND, composer.resolve("/Build now").kind());
  }

  @Test
  public void resolveUnknownSlashTextStaysAMessage() {
    loaded(cmd("build", null));

    CommandSelection selection = composer.resolve("/nope now");
    assertEquals(Kind.MESSAGE, selection.kind());
    assertEquals("/nope now", selection.message());
  }

  @Test
  public void resolvePlainAndMidTextSlashStayMessages() {
    loaded(cmd("build", null));

    assertEquals(Kind.MESSAGE, composer.resolve("hello /build").kind());
    assertEquals(Kind.MESSAGE, composer.resolve("hello").kind());
    assertEquals(Kind.MESSAGE, composer.resolve("").kind());
    CommandSelection none = composer.resolve(null);
    assertEquals(Kind.MESSAGE, none.kind());
    assertNull(none.message());
  }

  // ---------- helpers / fakes ----------

  private static CommandInfo cmd(String name, String description) {
    return new CommandInfo(name, description);
  }

  private static final class FakeConnection implements ChatServerConnection {
    final FakeClient client = new FakeClient();

    @Override
    public OpencodeClient getClient() {
      return client;
    }

    @Override
    public void addEventListener(OpencodeEventListener listener) {
      // not needed here
    }

    @Override
    public void removeEventListener(OpencodeEventListener listener) {
      // not needed here
    }
  }

  private static final class FakeClient implements OpencodeClient {
    List<CommandInfo> commands = List.of();
    OpencodeException failure;

    @Override
    public HealthStatus getHealth() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Agent> getAgents() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProviderList getProviders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ConfigInfo getConfig() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Session> getSessions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Session createSession(String title, Path directory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChatEntry sendMessage(ChatRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) {
      // not needed here
    }

    @Override
    public List<CommandInfo> getCommands() throws OpencodeException {
      if (failure != null) {
        throw failure;
      }
      return commands;
    }
  }
}
