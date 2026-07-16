package io.namei.agent.adapter.sqlite;

import java.nio.file.Path;
import java.sql.Connection;

@FunctionalInterface
interface ChannelLedgerBackup {
  void backup(Connection source, Path destination) throws Exception;
}
