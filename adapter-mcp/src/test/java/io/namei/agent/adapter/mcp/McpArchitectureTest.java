package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.Tool;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class McpArchitectureTest {
  private static final List<String> FORBIDDEN_API_PREFIXES =
      List.of("io.modelcontextprotocol.", "reactor.", "org.springframework.");

  @Test
  void publicAdapterBoundaryUsesOnlyJdkAndKernelTypes() throws Exception {
    assertThat(McpRuntime.class).isPublic();
    assertThat(McpRuntime.class.getMethod("tools").getGenericReturnType().getTypeName())
        .isEqualTo("java.util.List<" + Tool.class.getName() + ">");
    assertThat(McpRuntime.class.getMethods())
        .allSatisfy(
            method -> {
              assertAllowedApiType(method.getReturnType());
              for (Class<?> parameterType : method.getParameterTypes()) {
                assertAllowedApiType(parameterType);
              }
            });
    assertThat(McpRuntimeStatus.class).isPublic();
  }

  @Test
  void sdkGatewayRemainsPackagePrivate() {
    assertThat(Modifier.isPublic(McpSdkGateway.class.getModifiers())).isFalse();
    assertThat(McpSdkGateway.class.getDeclaredFields())
        .anySatisfy(
            field ->
                assertThat(field.getType().getName())
                    .startsWith("io.modelcontextprotocol.client."));
  }

  @Test
  void kernelAndApplicationSourcesDoNotImportMcpReactorOrSpringMcp() throws Exception {
    Path root = Path.of(System.getProperty("golden.root")).getParent().getParent();
    for (String module : List.of("agent-kernel", "agent-application")) {
      try (Stream<Path> sources = Files.walk(root.resolve(module).resolve("src/main/java"))) {
        for (Path path :
            sources.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
          String source = Files.readString(path);
          assertThat(source)
              .doesNotContain("io.modelcontextprotocol")
              .doesNotContain("reactor.")
              .doesNotContain("org.springframework.ai.mcp");
        }
      }
    }
  }

  private static void assertAllowedApiType(Class<?> type) {
    if (type.isPrimitive()) {
      return;
    }
    assertThat(FORBIDDEN_API_PREFIXES)
        .noneSatisfy(prefix -> assertThat(type.getName()).startsWith(prefix));
  }
}
