package com.opencode.ide.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.opencode.ide.chat.views.ChatView;

/**
 * Opens a chat window - either a NEW session (optionally with a preselected
 * model) or RESUMING an existing session. Invoked via the
 * {@code com.opencode.ide.chat.openChat} command with optional parameters
 * {@code providerId}, {@code modelId}, {@code sessionId}, so other bundles
 * (Providers/Server views) can launch chats without a compile dependency.
 */
public class OpenChatHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        String providerId = event.getParameter("com.opencode.ide.chat.openChat.providerId");
        String modelId = event.getParameter("com.opencode.ide.chat.openChat.modelId");
        String sessionId = event.getParameter("com.opencode.ide.chat.openChat.sessionId");
        String agentId = event.getParameter("com.opencode.ide.chat.openChat.agentId");
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window != null) {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                if (sessionId != null && !sessionId.isBlank()) {
                    ChatView.openResume(page, sessionId);
                } else {
                    ChatView chat = ChatView.openNew(page, providerId, modelId);
                    if (chat != null) {
                        chat.preselectAgent(agentId);
                    }
                }
            }
        }
        return null;
    }
}
