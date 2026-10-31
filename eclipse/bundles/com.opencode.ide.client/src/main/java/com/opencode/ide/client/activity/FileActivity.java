package com.opencode.ide.client.activity;

/** A file currently being worked on by an agent: which session, with which tool. */
public record FileActivity(String sessionId, String tool, String file) {}
