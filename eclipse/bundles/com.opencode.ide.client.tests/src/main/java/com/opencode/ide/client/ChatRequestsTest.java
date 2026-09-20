package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonParser;

/**
 * Unit tests for {@link ChatRequests} request-body building (pure, no I/O).
 *
 * <p>v2 split v1's single {@code POST /session/:id/message} body: the prompt
 * text goes to {@code POST /session/:id/prompt}, the agent to
 * {@code POST /session/:id/agent}, the model (with the variant folded in) to
 * {@code POST /session/:id/model}, and the per-request system prompt to
 * {@code POST /session/:id/synthetic}.</p>
 */
public class ChatRequestsTest {

    private static ChatRequest request(String agent, String provider, String model, String text) {
        return new ChatRequest("ses_1", agent, provider, model, null, null, text);
    }

    @Test
    public void promptBodyCarriesJustTheText() {
        var obj = JsonParser.parseString(
                ChatRequests.promptBody(request("build", "opencode", "glm-5.2", "hello **world**")))
                .getAsJsonObject();
        assertEquals("hello **world**", obj.get("text").getAsString());
        assertFalse("agent/model/system are no longer prompt fields", obj.has("agent"));
        assertFalse(obj.has("model"));
        assertFalse(obj.has("parts"));
    }

    @Test
    public void nullTextBecomesEmptyAndNewlinesSurvive() {
        var obj = JsonParser.parseString(
                ChatRequests.promptBody(request("build", null, null, "line1\nline2 \"quoted\" <b>")))
                .getAsJsonObject();
        assertEquals("line1\nline2 \"quoted\" <b>", obj.get("text").getAsString());
        var nullObj = JsonParser.parseString(ChatRequests.promptBody(request("build", null, null, null)))
                .getAsJsonObject();
        assertEquals("", nullObj.get("text").getAsString());
    }

    @Test
    public void agentBodyIsNullWithoutAnAgent() {
        assertNull(ChatRequests.agentBody(request(null, null, null, "hi")));
        assertNull(ChatRequests.agentBody(request("  ", null, null, "hi")));
    }

    @Test
    public void agentBodyCarriesTheAgentName() {
        var obj = JsonParser.parseString(ChatRequests.agentBody(request("build", null, null, "hi")))
                .getAsJsonObject();
        assertEquals("build", obj.get("agent").getAsString());
    }

    @Test
    public void modelBodyUsesTheV2ModelRefShape() {
        var obj = JsonParser.parseString(ChatRequests.modelBody(request("build", "opencode", "glm-5.2", "hi")))
                .getAsJsonObject();
        var model = obj.getAsJsonObject("model");
        assertEquals("glm-5.2", model.get("id").getAsString());
        assertEquals("opencode", model.get("providerID").getAsString());
        assertFalse("no variant selected -> omitted", model.has("variant"));
    }

    @Test
    public void variantFoldsIntoTheModelRef() {
        var obj = JsonParser.parseString(ChatRequests.modelBody(
                request("build", "opencode-go", "gpt-5.6-luna", "hi").withVariant("high")))
                .getAsJsonObject();
        assertEquals("high", obj.getAsJsonObject("model").get("variant").getAsString());
    }

    @Test
    public void halfSetModelProducesNoBody() {
        assertNull(ChatRequests.modelBody(request("build", "opencode", null, "hi")));
        assertNull(ChatRequests.modelBody(request("build", null, "glm-5.2", "hi")));
        assertNull(ChatRequests.modelBody(request("build", null, null, "hi").withVariant("high")));
    }

    @Test
    public void systemPromptBecomesASyntheticMessage() {
        String json = ChatRequests.syntheticBody(
                request("build", null, null, "hi").withSystem(ChatCapabilities.RENDERER_SYSTEM_PROMPT));
        var obj = JsonParser.parseString(json).getAsJsonObject();
        String system = obj.get("text").getAsString();
        assertTrue(system.contains("KaTeX") || system.contains("$$"));
        assertTrue("must advertise language-tagged code fences", system.contains("```cpp"));
        assertTrue("must advertise mermaid", system.contains("mermaid"));
    }

    @Test
    public void systemPromptIsNullWhenUnset() {
        assertNull(ChatRequests.syntheticBody(request("build", null, null, "hi")));
        assertNull(ChatRequests.syntheticBody(request("build", null, null, "hi").withSystem("  ")));
    }

    @Test
    public void withersDoNotMutateTheOriginalRequest() {
        ChatRequest base = ChatRequest.of("ses_1", "hi");
        ChatRequest derived = base.withAgent("build").withModel("p", "m").withVariant("max");
        assertEquals(null, base.agent());
        assertFalse(base.hasModel());
        assertEquals("build", derived.agent());
        assertTrue(derived.hasModel());
        assertEquals("max", derived.variant());
        assertEquals("hi", derived.text());
    }
}
