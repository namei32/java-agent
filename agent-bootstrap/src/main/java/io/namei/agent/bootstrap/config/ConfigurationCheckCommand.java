package io.namei.agent.bootstrap.config;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** 在启动应用前执行只读配置检查。 */
public final class ConfigurationCheckCommand {
  private static final String CHECK_SWITCH = "--agent.config-check";
  private static final String CONFIG_FILE_PREFIX = "--agent.config-file=";
  private static final Set<String> RUNTIME_ONLY_SECRETS = Set.of("AGENT_TELEGRAM_BOT_TOKEN");
  private static final ObjectMapper JSON = new ObjectMapper();

  private ConfigurationCheckCommand() {}

  public static boolean isRequested(String[] args) {
    Objects.requireNonNull(args, "args");
    for (String argument : args) {
      if (CHECK_SWITCH.equals(argument) || (CHECK_SWITCH + "=true").equals(argument)) {
        return true;
      }
    }
    return false;
  }

  public static int run(
      String[] args, Map<String, String> environment, Path workingDirectory, PrintStream output) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(environment, "environment");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    Objects.requireNonNull(output, "output");

    var inputs =
        new ConfigurationInputs(
            workingDirectory, commandLineConfigFile(args), configurationEnvironment(environment));
    ConfigurationResolution resolution = new ConfigurationResolver().resolve(inputs);
    boolean valid = resolution.active().isPresent();

    JSON.writerWithDefaultPrettyPrinter().writeValue(output, report(resolution, valid));
    output.println();
    return valid ? 0 : 2;
  }

  private static String commandLineConfigFile(String[] args) {
    String selected = null;
    for (String argument : args) {
      if (argument.startsWith(CONFIG_FILE_PREFIX)) {
        selected = argument.substring(CONFIG_FILE_PREFIX.length());
      }
    }
    return selected;
  }

  private static Map<String, String> configurationEnvironment(Map<String, String> environment) {
    var filtered = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      String name = Objects.requireNonNull(entry.getKey(), "environment key");
      if (RUNTIME_ONLY_SECRETS.contains(name)) {
        continue;
      }
      filtered.put(name, Objects.requireNonNull(entry.getValue(), "environment value"));
    }
    return Map.copyOf(filtered);
  }

  private static Map<String, Object> report(ConfigurationResolution resolution, boolean valid) {
    var result = new LinkedHashMap<String, Object>();
    result.put("valid", valid);
    result.put("mode", resolution.mode().name());
    result.put(
        "configFile",
        resolution
            .configFile()
            .map(path -> path.toAbsolutePath().normalize().toString())
            .orElse(null));
    result.put("active", activeFields(resolution.report()));
    result.put("deferredPaths", resolution.report().deferredPaths());
    result.put("unknownPaths", resolution.report().unknownPaths());
    result.put("diagnostics", diagnostics(resolution.report()));
    return result;
  }

  private static ArrayList<Map<String, String>> activeFields(ConfigurationReport report) {
    var fields = new ArrayList<Map<String, String>>();
    report
        .activeSources()
        .forEach(
            (field, source) -> {
              var item = new LinkedHashMap<String, String>();
              item.put("field", field);
              item.put("source", source.name());
              SecretStatus status = report.sensitiveStatuses().get(field);
              if (status != null) {
                item.put("status", status.name());
              }
              fields.add(item);
            });
    return fields;
  }

  private static ArrayList<Map<String, String>> diagnostics(ConfigurationReport report) {
    var diagnostics = new ArrayList<Map<String, String>>();
    for (ConfigurationDiagnostic diagnostic : report.diagnostics()) {
      diagnostics.add(Map.of("code", diagnostic.code().name(), "field", diagnostic.field()));
    }
    return diagnostics;
  }
}
