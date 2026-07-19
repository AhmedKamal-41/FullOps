package com.ahmedali.fulfillops.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every fixture under contracts/events/examples/ must validate against the schema its filename
 * names, and its eventType/eventVersion fields must match that schema's const values. This is what
 * keeps a schema and its documented example from drifting apart — see contracts/README.md for what
 * this test does and doesn't prove about version compatibility.
 */
class EventSchemaValidationTest {

  private static final Path EVENTS_DIR = Path.of("events");
  private static final Path EXAMPLES_DIR = EVENTS_DIR.resolve("examples");
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> everyExampleFixtureValidatesAgainstItsSchema() throws IOException {
    SchemaRegistry registry = loadRegistry();

    return listExampleFiles()
        .map(
            exampleFile ->
                dynamicTest(
                    exampleFile.getFileName().toString(),
                    () -> {
                      String schemaId = schemaIdFor(exampleFile);
                      Schema schema = registry.getSchema(SchemaLocation.of(schemaId));
                      JsonNode example = JSON.readTree(exampleFile.toFile());

                      List<Error> errors = schema.validate(example);

                      assertThat(errors)
                          .as(
                              "validation errors for %s: %s",
                              exampleFile.getFileName(),
                              errors.stream()
                                  .map(Error::getMessage)
                                  .collect(Collectors.joining("; ")))
                          .isEmpty();
                    }));
  }

  @Test
  void everySchemaFileHasAMatchingExample() throws IOException {
    List<String> schemaEventNames =
        listSchemaFiles()
            .map(f -> f.getFileName().toString())
            .filter(
                name ->
                    !name.equals("EventEnvelope.v1.schema.json")
                        && !name.equals("Money.v1.schema.json"))
            .toList();

    for (String schemaFileName : schemaEventNames) {
      String expectedExampleName = schemaFileName.replace(".schema.json", ".example.json");
      assertThat(EXAMPLES_DIR.resolve(expectedExampleName))
          .as("expected an example fixture for %s", schemaFileName)
          .exists();
    }
  }

  private static String schemaIdFor(Path exampleFile) throws IOException {
    String schemaFileName =
        exampleFile.getFileName().toString().replace(".example.json", ".schema.json");
    return "https://fulfillops.dev/contracts/events/" + schemaFileName;
  }

  private static SchemaRegistry loadRegistry() throws IOException {
    Map<String, String> contentById = new HashMap<>();
    for (Path schemaFile : listSchemaFiles().toList()) {
      String content = Files.readString(schemaFile);
      String id = JSON.readTree(content).get("$id").asString();
      contentById.put(id, content);
    }
    return SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(contentById));
  }

  private static Stream<Path> listSchemaFiles() throws IOException {
    return Files.list(EVENTS_DIR)
        .filter(f -> f.toString().endsWith(".schema.json"))
        .sorted(Comparator.naturalOrder());
  }

  private static Stream<Path> listExampleFiles() throws IOException {
    return Files.list(EXAMPLES_DIR)
        .filter(f -> f.toString().endsWith(".example.json"))
        .sorted(Comparator.naturalOrder());
  }
}
