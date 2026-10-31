package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.Session;
import org.junit.Test;

/**
 * Unit tests for {@link OpencodeEvent}'s pure navigation helpers ({@code string}, {@code at},
 * {@code as}) - the contract the UI relies on for live event parsing.
 */
public class OpencodeEventTest {

  private static OpencodeEvent event(String propertiesJson) {
    JsonObject properties = JsonParser.parseString(propertiesJson).getAsJsonObject();
    return new OpencodeEvent("session.status", properties);
  }

  @Test
  public void stringReadsPrimitiveProperty() {
    OpencodeEvent event = event("{\"sessionID\":\"ses_1\"}");
    assertEquals("ses_1", event.string("sessionID"));
  }

  @Test
  public void stringReturnsNullForMissingOrObject() {
    OpencodeEvent event = event("{\"status\":{\"type\":\"busy\"}}");
    assertNull(event.string("status")); // object, not primitive
    assertNull(event.string("missing"));
  }

  @Test
  public void atNavigatesDotPath() {
    OpencodeEvent event =
        event("{\"part\":{\"type\":\"reasoning\",\"state\":{\"status\":\"running\"}}}");
    assertEquals("reasoning", event.at("part.type"));
    assertEquals("running", event.at("part.state.status"));
  }

  @Test
  public void atReturnsNullForMissingPathSegment() {
    OpencodeEvent event = event("{\"part\":{\"type\":\"text\"}}");
    assertNull(event.at("part.state.status"));
    assertNull(event.at("does.not.exist"));
  }

  @Test
  public void asDeserializesNestedObject() {
    OpencodeEvent event =
        event("{\"info\":{\"id\":\"ses_1\",\"title\":\"hello\",\"agent\":\"build\"}}");
    Session session = event.as("info", Session.class);
    assertEquals("ses_1", session.id());
    assertEquals("hello", session.title());
    assertEquals("build", session.agent());
  }

  @Test
  public void asReturnsNullForMissing() {
    assertNull(event("{}").as("info", Session.class));
  }
}
