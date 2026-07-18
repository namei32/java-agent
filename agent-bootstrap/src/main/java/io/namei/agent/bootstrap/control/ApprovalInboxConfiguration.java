package io.namei.agent.bootstrap.control;

import io.namei.agent.adapter.sqlite.ApprovalInboxSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcApprovalInbox;
import io.namei.agent.application.ApprovalInbox;
import io.namei.agent.bootstrap.config.AgentProperties;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalInboxProperties.class)
public class ApprovalInboxConfiguration {
  private static final String PREFIX = "agent.approval-inbox";

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ApprovalInboxSchemaInitializer approvalInboxSchema(
      AgentProperties agentProperties,
      ApprovalInboxProperties inboxProperties,
      ObjectProvider<ControlPlaneProperties> controlProperties) {
    ControlPlaneProperties controls = controlProperties.getIfAvailable();
    if (inboxProperties.mode() != ApprovalInboxMode.LOOPBACK
        || controls == null
        || controls.mode() != ControlPlaneMode.LOOPBACK) {
      throw new IllegalStateException("启用审批收件箱前必须启用 Loopback 控制面");
    }
    var schema =
        new ApprovalInboxSchemaInitializer(
            agentProperties.workspace().resolve("approval-inbox.db"), 5_000);
    schema.initialize();
    return schema;
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ApprovalInbox approvalInbox(ApprovalInboxSchemaInitializer schema) {
    return new JdbcApprovalInbox(schema);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ApprovalInboxControlService approvalInboxControlService(
      ApprovalInbox inbox, ObjectProvider<Clock> clocks) {
    return new ApprovalInboxControlService(clocks.getIfAvailable(Clock::systemUTC), inbox);
  }
}
