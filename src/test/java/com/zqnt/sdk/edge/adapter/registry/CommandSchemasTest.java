package com.zqnt.sdk.edge.adapter.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Params arrive as a protobuf Struct: every number a double. The schema decides once, centrally,
 * what an integer is, and a wrong param is refused with a message a person can act on.
 */
class CommandSchemasTest {

	private static final Map<String, Object> GO_TO = Map.of(
			"type", "object",
			"required", List.of("latitude", "longitude"),
			"properties", Map.of(
					"latitude", Map.of("type", "number", "minimum", -90, "maximum", 90),
					"longitude", Map.of("type", "number", "minimum", -180, "maximum", 180),
					"altitude", Map.of("type", "number"),
					"speed", Map.of("type", "integer", "exclusiveMinimum", 0),
					"mode", Map.of("type", "string", "enum", List.of("FAST", "SAFE"))),
			"additionalProperties", false);

	@Test
	void aStructDoubleBecomesAnIntegerWhereTheSchemaSaysInteger() {
		var validation = CommandSchemas.validate(GO_TO, Map.of("latitude", 52.5, "longitude", 13.4, "speed", 12.0));

		assertTrue(validation.valid(), validation.message());
		assertEquals(12, validation.params().get("speed"));
		assertInstanceOf(Integer.class, validation.params().get("speed"));
		assertEquals(52.5, validation.params().get("latitude"));
	}

	@Test
	void aFractionIsNoInteger() {
		var validation = CommandSchemas.validate(GO_TO, Map.of("latitude", 52.5, "longitude", 13.4, "speed", 1.5));

		assertFalse(validation.valid());
		assertEquals(List.of("speed must be an integer, got the number 1.5"), validation.errors());
	}

	@Test
	void everyProblemIsNamedReadably() {
		var validation = CommandSchemas.validate(GO_TO,
				Map.of("latitude", "north", "longitude", 200.0, "mode", "TURBO", "colour", "red"));

		assertEquals(List.of(
				"latitude must be a number, got text \"north\"",
				"longitude must be at most 180",
				"mode must be one of [\"FAST\", \"SAFE\"], got \"TURBO\"",
				"colour is not a known parameter"), validation.errors().stream().sorted((a, b) ->
				order(a) - order(b)).toList());
	}

	private static int order(String error) {
		return List.of("latitude", "longitude", "mode", "colour").indexOf(error.split(" ")[0]);
	}

	@Test
	void aMissingRequiredParamIsRefused() {
		var validation = CommandSchemas.validate(GO_TO, Map.of("latitude", 52.5));

		assertEquals(List.of("longitude is required"), validation.errors());
	}

	@Test
	void nestedObjectsAndListsAreCheckedAndCoerced() {
		Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
				"waypoints", Map.of("type", "array", "minItems", 1, "items", Map.of(
						"type", "object", "required", List.of("index"),
						"properties", Map.of("index", Map.of("type", "integer"))))));

		var valid = CommandSchemas.validate(schema, Map.of("waypoints", List.of(Map.of("index", 0.0), Map.of("index", 1.0))));
		var invalid = CommandSchemas.validate(schema, Map.of("waypoints", List.of(Map.of("height", 3.0))));

		assertTrue(valid.valid(), valid.message());
		@SuppressWarnings("unchecked")
		var first = (Map<String, Object>) ((List<?>) valid.params().get("waypoints")).get(0);
		assertEquals(0, first.get("index"));
		assertEquals(List.of("waypoints[0].index is required"), invalid.errors());
	}

	@Test
	void withoutASchemaEverythingPasses() {
		var validation = CommandSchemas.validate(Map.of(), Map.of("anything", 1.0));

		assertTrue(validation.valid());
		assertEquals(1.0, validation.params().get("anything"));
	}

	@Test
	void aBrokenSchemaIsReported() {
		var problems = CommandSchemas.checkSchema(Map.of(
				"type", "obj",
				"required", "latitude",
				"properties", Map.of("zoom", Map.of("type", "number", "minimum", "low"), "lens", Map.of("pattern", "("))));

		assertEquals(4, problems.size(), problems.toString());
		assertTrue(problems.contains("schema.type has an unknown type \"obj\""));
		assertTrue(problems.contains("schema.required is not a list of names"));
		assertTrue(problems.contains("schema.properties.zoom.minimum is not a number"));
		assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("schema.properties.lens.pattern does not compile")));
	}

	@Test
	void aSampleSatisfiesItsOwnSchema() {
		@SuppressWarnings("unchecked")
		var sample = (Map<String, Object>) CommandSchemas.sample(GO_TO);

		assertTrue(CommandSchemas.validate(GO_TO, sample).valid(), sample.toString());
		assertEquals(Map.of("latitude", -90.0, "longitude", -180.0), sample);
	}
}
