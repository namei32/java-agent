package io.namei.agent.bootstrap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.namei.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule kernel_has_no_framework_dependencies =
      noClasses()
          .that()
          .resideInAPackage("io.namei.agent.kernel..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "java.sql..",
              "reactor..",
              "com.openai..",
              "io.namei.agent.application..",
              "io.namei.agent.adapter..",
              "io.namei.agent.bootstrap..");

  @ArchTest
  static final ArchRule application_depends_only_on_jdk_and_kernel =
      noClasses()
          .that()
          .resideInAPackage("io.namei.agent.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "java.sql..",
              "reactor..",
              "com.openai..",
              "io.namei.agent.adapter..",
              "io.namei.agent.bootstrap..");
}
