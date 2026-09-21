package com.opencode.ide.client.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.Sse;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Tests for {@link PermissionEvents} using v2 wire frames captured from the
 * live server: request identity, optional tool metadata, display hints and
 * malformed-event tolerance. Frame ids, request ids and tool-call ids differ.
 */
public class PermissionEventsTest {

    private static OpencodeEvent event(String json) {
        return Sse.parseEvent(json);
    }

    @Test
    public void askedEventParsesIdsCategoryResourcesAndMetadataTitle() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"id":"evt_1","created":1,"type":"permission.asked",
                 "location":{"directory":"C:/repo"},"data":{
                  "id":"per_1","sessionID":"ses_1","action":"shell",
                  "resources":["git push","git status"],
                  "save":["git *"],
                  "metadata":{"command":"git push","cwd":"/repo"},
                  "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}}"""));
        assertNotNull(request);
        assertEquals("ses_1", request.sessionId());
        assertEquals("per_1", request.permissionId());
        assertEquals("shell", request.permission());
        assertEquals(List.of("git push", "git status"), request.patterns());
        assertEquals("metadata.command is the display title", "git push", request.title());
        assertEquals(PermissionRequest.Status.PENDING, request.status());
        assertTrue(request.pending());
    }

    @Test
    public void askedWithoutToolSourceStillHasAnActionableRequestId() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"id":"evt_2","created":2,"type":"permission.asked","data":{
                  "id":"per_2","sessionID":"ses_1","action":"shell",
                  "resources":["git status"],"save":[]}}"""));
        assertNotNull(request);
        assertEquals("per_2", request.permissionId());
        assertEquals("shell: git status", request.display());
        assertTrue(request.pending());
    }

    @Test
    public void askedWithoutMetadataTitleUsesPathTitleKeyAndSkipsNonStrings() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"type":"permission.asked","data":{
                  "id":"per_2","sessionID":"ses_1","action":"edit",
                  "resources":[42,"src/A.java",true,null,{}],
                  "metadata":{"path":"src/A.java","title":"  "}}}"""));
        // blank "title" is skipped, "path" is the next candidate
        assertEquals("src/A.java", request.title());
        // non-string resource entries are dropped
        assertEquals(List.of("src/A.java"), request.patterns());
        assertEquals("edit: src/A.java", request.display());
    }

    @Test
    public void askedWithoutTitleOrResourcesDisplaysBareCategory() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"type":"permission.asked","data":{
                  "id":"per_3","sessionID":"ses_2","action":"webfetch"}}"""));
        assertNull(request.title());
        assertEquals(List.of(), request.patterns());
        assertEquals("webfetch", request.display());
    }

    @Test
    public void repliedEventParsesRequestIDAsAnswered() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"id":"evt_3","type":"permission.replied","data":{
                  "sessionID":"ses_1","requestID":"per_1","reply":"once"}}"""));
        assertEquals("ses_1", request.sessionId());
        assertEquals("per_1", request.permissionId());
        assertEquals(PermissionRequest.Status.ANSWERED, request.status());
        assertEquals(false, request.pending());
    }

    @Test
    public void permissionEventsWithoutActionableIdsReturnNull() {
        assertNull(PermissionEvents.parse(event(
                "{\"type\":\"permission.asked\",\"data\":{\"action\":\"shell\"}}")));
        assertNull(PermissionEvents.parse(event(
                "{\"type\":\"permission.asked\",\"data\":{\"id\":\"per_1\"}}")));
        assertNull(PermissionEvents.parse(event(
                "{\"type\":\"permission.replied\",\"data\":{\"reply\":\"once\"}}")));
        assertNull(PermissionEvents.parse(event(
                "{\"type\":\"permission.asked\",\"data\":{}}")));
    }

    @Test
    public void toolIdentityAndLegacyIdAliasesAreNotRequestIds() {
        assertNull("source.id cannot stand in for the missing request id",
                PermissionEvents.parse(event("""
                        {"id":"evt_1","type":"permission.asked","data":{
                          "sessionID":"ses_1","action":"shell","resources":[],
                          "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}}""")));
        assertNull("the session key is sessionID",
                PermissionEvents.parse(event("""
                        {"type":"permission.asked","data":{
                          "id":"per_1","sessionId":"ses_1","action":"shell"}}""")));
        assertNull("the reply key is requestID",
                PermissionEvents.parse(event("""
                        {"type":"permission.replied","data":{
                          "sessionID":"ses_1","permissionID":"per_1","reply":"once"}}""")));
    }

    @Test
    public void blankAndNonStringIdentifiersAreIgnored() {
        for (String value : List.of("null", "\"\"", "\"   \"", "42", "true", "{}", "[]")) {
            assertNull("invalid ask id: " + value, PermissionEvents.parse(event("""
                    {"type":"permission.asked","data":{"sessionID":"ses_1","id":%s}}
                    """.formatted(value))));
            assertNull("invalid reply id: " + value, PermissionEvents.parse(event("""
                    {"type":"permission.replied","data":{"sessionID":"ses_1","requestID":%s}}
                    """.formatted(value))));
            assertNull("invalid ask session: " + value, PermissionEvents.parse(event("""
                    {"type":"permission.asked","data":{"sessionID":%s,"id":"per_1"}}
                    """.formatted(value))));
            assertNull("invalid reply session: " + value, PermissionEvents.parse(event("""
                    {"type":"permission.replied","data":{"sessionID":%s,"requestID":"per_1"}}
                    """.formatted(value))));
        }
    }

    @Test
    public void legacyDisplayAliasesDoNotOverrideV2Fields() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"type":"permission.asked","data":{
                  "id":"per_1","sessionID":"ses_1","action":"shell","resources":[],
                  "permission":"edit","patterns":["legacy"]}}"""));
        assertEquals("shell", request.permission());
        assertTrue("an empty resources array stays empty", request.patterns().isEmpty());
    }

    @Test
    public void nonPermissionAndMalformedEventsReturnNull() {
        assertNull(PermissionEvents.parse(null));
        assertNull(PermissionEvents.parse(new OpencodeEvent(null, null)));
        assertNull(PermissionEvents.parse(new OpencodeEvent("permission.asked", null)));
        assertNull(PermissionEvents.parse(event("{\"type\":\"session.idle\",\"data\":{\"sessionID\":\"ses_1\"}}")));
        assertNull(PermissionEvents.parse(event("{\"type\":\"session.text.delta\",\"data\":{}}")));
        assertNull(PermissionEvents.parse(event("{\"type\":\"todo.updated\",\"data\":{}}")));
        assertNull(PermissionEvents.parse(event("{\"type\":\"permission.updated\",\"data\":{}}")));
    }

    @Test
    public void missingOrNullMetadataAndResourcesAreTolerated() {
        PermissionRequest request = PermissionEvents.parse(event("""
                {"type":"permission.asked","data":{
                  "id":"per_4","sessionID":"ses_3","resources":"not-an-array",
                  "metadata":"not-an-object"}}"""));
        assertEquals(List.of(), request.patterns());
        assertNull(request.title());
        assertNull(request.permission());
    }
}
