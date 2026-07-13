package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatMessage;

public record ChatResult(String sessionId, ChatMessage assistant) {}
