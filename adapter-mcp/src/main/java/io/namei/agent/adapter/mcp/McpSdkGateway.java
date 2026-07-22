package io.namei.agent.adapter.mcp;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** 封装适配器所用全部响应式 SDK 操作的包内边界。 */
final class McpSdkGateway implements AutoCloseable {
  private final McpAsyncClient client;

  McpSdkGateway(McpAsyncClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  Mono<McpSchema.InitializeResult> initialize() {
    return client.initialize();
  }

  Mono<McpSchema.ListToolsResult> listTools(String cursor) {
    return client.listTools(cursor);
  }

  Mono<McpSchema.ListResourcesResult> listResources(String cursor) {
    return client.listResources(cursor);
  }

  Mono<McpSchema.ListPromptsResult> listPrompts(String cursor) {
    return client.listPrompts(cursor);
  }

  Mono<McpSchema.CallToolResult> callTool(String remoteName, Map<String, Object> arguments) {
    McpSchema.CallToolRequest request =
        McpSchema.CallToolRequest.builder(remoteName).arguments(Map.copyOf(arguments)).build();
    return client.callTool(request);
  }

  Mono<Void> closeGracefully() {
    return client.closeGracefully();
  }

  @Override
  public void close() {
    client.close();
  }
}
