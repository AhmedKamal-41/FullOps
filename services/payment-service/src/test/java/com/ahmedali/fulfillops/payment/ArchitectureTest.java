package com.ahmedali.fulfillops.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Guards this service's package boundaries and its isolation from the other three services.
 *
 * <p>Each service is its own Maven module and does not depend on a sibling, so the cross-service
 * rule below can only ever be broken by someone deliberately adding such a dependency — which is
 * exactly the "distributed monolith" mistake this project forbids and this test exists to catch
 * early. The layering rules keep the domain independent of the delivery mechanisms (HTTP, Kafka),
 * so business logic never reaches back out to a controller or a message listener.
 *
 * <p>The layer matchers are fully qualified under this service's own base package on purpose: a
 * bare "..web.." would also match Spring's own org.springframework.web packages and produce false
 * violations.
 */
class ArchitectureTest {

  private static final String BASE = "com.ahmedali.fulfillops.payment";

  private static final JavaClasses SERVICE_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  void doesNotDependOnAnotherService() {
    ArchRule rule =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.ahmedali.fulfillops.order..",
                "com.ahmedali.fulfillops.inventory..",
                "com.ahmedali.fulfillops.fulfillment..");
    rule.check(SERVICE_CLASSES);
  }

  @Test
  void domainDoesNotDependOnWebOrMessaging() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(BASE + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BASE + ".web..", BASE + ".messaging..");
    rule.check(SERVICE_CLASSES);
  }

  @Test
  void messagingDoesNotDependOnWeb() {
    // Event listeners react to Kafka messages; they must not reach back into the HTTP
    // delivery layer. (The reverse — the admin dead-letter controller reading the
    // dead-letter store in the messaging package — is a deliberate, allowed direction.)
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(BASE + ".messaging..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(BASE + ".web..");
    rule.check(SERVICE_CLASSES);
  }
}
