package io.namei.agent.adapter.sqlite;

import java.sql.SQLException;

@FunctionalInterface
interface ChannelLedgerFaultInjector {
  void hit(ChannelLedgerFaultPoint point) throws SQLException;
}
