package io.namei.agent.bootstrap.cli;

@FunctionalInterface
public interface CliInput {
  /** 返回下一行；到达 EOF 时返回 {@code null}。 */
  String readLine();
}
