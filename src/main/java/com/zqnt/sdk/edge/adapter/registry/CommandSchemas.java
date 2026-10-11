package com.zqnt.sdk.edge.adapter.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The part of JSON Schema a command's input and output schemas use: {@code type} (one or a list),
 * {@code properties}, {@code required}, {@code additionalProperties}, {@code items},
 * {@code enum}, {@code const}, numeric and length bounds, {@code pattern}, {@code minItems}/
 * {@code maxItems}, {@code anyOf}/{@code oneOf}/{@code allOf}. Unknown keywords are ignored.
 *
 * <p>Params arrive as a protobuf Struct, where every number is a double. Validation converts a
 * number to {@link Integer} (or {@link Long}) wherever the schema says {@code integer}, once, so no
 * handler has to; a fractional value there is an error.
 */
public final class CommandSchemas {

	private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");

	private CommandSchemas() {
	}

	/** The coerced params and every problem found; valid when there are no problems. */
	public record Validation(Map<String, Object> params, List<String> errors) {
		public boolean valid() {
			return errors.isEmpty();
		}

		public String message() {
			return String.join("; ", errors);
		}
	}

	public static Validation validate(Map<String, Object> schema, Map<String, Object> params) {
		Map<String, Object> input = params == null ? Map.of() : params;
		if (schema == null || schema.isEmpty()) {
			return new Validation(input, List.of());
		}
		List<String> errors = new ArrayList<>();
		Object coerced = check(schema, input, "params", errors);
		@SuppressWarnings("unchecked")
		Map<String, Object> result = coerced instanceof Map<?, ?> map ? (Map<String, Object>) map : input;
		return new Validation(result, List.copyOf(errors));
	}

	/** Problems that make {@code schema} unusable; empty when it is a schema this class understands. */
	public static List<String> checkSchema(Map<String, Object> schema) {
		List<String> problems = new ArrayList<>();
		if (schema != null) {
			checkSchema(schema, "schema", problems);
		}
		return problems;
	}

	/** A minimal value that satisfies {@code schema}: defaults, constants and enums first, then bounds. */
	public static Object sample(Map<String, Object> schema) {
		if (schema == null || schema.isEmpty()) {
			return Map.of();
		}
		if (schema.containsKey("default")) return schema.get("default");
		if (schema.containsKey("const")) return schema.get("const");
		if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) return values.getFirst();
		if (schema.get("examples") instanceof List<?> examples && !examples.isEmpty()) return examples.getFirst();
		for (String combinator : List.of("anyOf", "oneOf", "allOf")) {
			if (schema.get(combinator) instanceof List<?> options && !options.isEmpty()
					&& options.getFirst() instanceof Map<?, ?> first) {
				return sample(asSchema(first));
			}
		}
		List<String> types = types(schema);
		String type = types.isEmpty() ? (schema.containsKey("properties") ? "object" : "null") : types.getFirst();
		return switch (type) {
			case "object" -> {
				Map<String, Object> value = new LinkedHashMap<>();
				Map<String, Object> properties = properties(schema);
				for (Object name : list(schema.get("required"))) {
					Object property = properties.get(String.valueOf(name));
					value.put(String.valueOf(name), property instanceof Map<?, ?> map ? sample(asSchema(map)) : "");
				}
				yield value;
			}
			case "array" -> {
				List<Object> value = new ArrayList<>();
				int count = schema.get("minItems") instanceof Number number ? number.intValue() : 0;
				Map<String, Object> items = schema.get("items") instanceof Map<?, ?> map ? asSchema(map) : Map.of();
				for (int i = 0; i < count; i++) value.add(sample(items));
				yield value;
			}
			case "string" -> "x".repeat(Math.max(1, schema.get("minLength") instanceof Number n ? n.intValue() : 1));
			case "number" -> lowerBound(schema, false);
			case "integer" -> (int) Math.ceil(lowerBound(schema, true));
			case "boolean" -> false;
			default -> null;
		};
	}

	private static double lowerBound(Map<String, Object> schema, boolean integer) {
		double step = integer ? 1 : 0.5;
		if (schema.get("exclusiveMinimum") instanceof Number exclusive) return exclusive.doubleValue() + step;
		if (schema.get("minimum") instanceof Number minimum) {
			return Boolean.TRUE.equals(schema.get("exclusiveMinimum")) ? minimum.doubleValue() + step : minimum.doubleValue();
		}
		if (schema.get("exclusiveMaximum") instanceof Number exclusive && exclusive.doubleValue() <= 0) return exclusive.doubleValue() - step;
		if (schema.get("maximum") instanceof Number maximum && maximum.doubleValue() < 0) return maximum.doubleValue();
		return 0;
	}

	private static Object check(Map<String, Object> schema, Object value, String path, List<String> errors) {
		if (schema == null || schema.isEmpty()) {
			return value;
		}
		Object current = value;
		for (String combinator : List.of("anyOf", "oneOf")) {
			if (schema.get(combinator) instanceof List<?> options && !options.isEmpty()) {
				Object matched = null;
				boolean any = false;
				for (Object option : options) {
					if (!(option instanceof Map<?, ?> map)) continue;
					List<String> optionErrors = new ArrayList<>();
					Object coerced = check(asSchema(map), current, path, optionErrors);
					if (optionErrors.isEmpty()) {
						matched = coerced;
						any = true;
						break;
					}
				}
				if (!any) {
					errors.add(path + " matches none of the allowed shapes");
					return current;
				}
				current = matched;
			}
		}
		if (schema.get("allOf") instanceof List<?> all) {
			for (Object option : all) {
				if (option instanceof Map<?, ?> map) current = check(asSchema(map), current, path, errors);
			}
		}

		List<String> types = types(schema);
		final Object checked = coerce(types, current);
		if (!types.isEmpty() && types.stream().noneMatch(type -> matches(type, checked))) {
			errors.add(path + " must be " + describe(types) + ", got " + describeValue(checked));
			return checked;
		}
		if (schema.containsKey("const") && !same(schema.get("const"), checked)) {
			errors.add(path + " must be " + render(schema.get("const")));
		}
		if (schema.get("enum") instanceof List<?> allowed && allowed.stream().noneMatch(option -> same(option, checked))) {
			errors.add(path + " must be one of " + render(allowed) + ", got " + render(checked));
		}
		if (checked instanceof Number number) {
			checkBounds(schema, number.doubleValue(), path, errors);
		}
		if (checked instanceof CharSequence text) {
			checkString(schema, text.toString(), path, errors);
		}
		if (checked instanceof List<?> items) {
			return checkArray(schema, items, path, errors);
		}
		if (checked instanceof Map<?, ?> map) {
			return checkObject(schema, map, path, errors);
		}
		return checked;
	}

	private static Object coerce(List<String> types, Object value) {
		if (value instanceof Number number && !(value instanceof Integer || value instanceof Long)
				&& types.contains("integer") && !types.contains("number")) {
			double raw = number.doubleValue();
			if (Double.isFinite(raw) && raw == Math.rint(raw)) {
				return raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE ? (Object) (int) raw : (Object) (long) raw;
			}
		}
		return value;
	}

	private static void checkBounds(Map<String, Object> schema, double value, String path, List<String> errors) {
		boolean legacyExclusiveMin = Boolean.TRUE.equals(schema.get("exclusiveMinimum"));
		boolean legacyExclusiveMax = Boolean.TRUE.equals(schema.get("exclusiveMaximum"));
		if (schema.get("minimum") instanceof Number minimum) {
			if (legacyExclusiveMin ? value <= minimum.doubleValue() : value < minimum.doubleValue()) {
				errors.add(path + " must be " + (legacyExclusiveMin ? "greater than " : "at least ") + render(minimum));
			}
		}
		if (schema.get("maximum") instanceof Number maximum) {
			if (legacyExclusiveMax ? value >= maximum.doubleValue() : value > maximum.doubleValue()) {
				errors.add(path + " must be " + (legacyExclusiveMax ? "less than " : "at most ") + render(maximum));
			}
		}
		if (schema.get("exclusiveMinimum") instanceof Number minimum && value <= minimum.doubleValue()) {
			errors.add(path + " must be greater than " + render(minimum));
		}
		if (schema.get("exclusiveMaximum") instanceof Number maximum && value >= maximum.doubleValue()) {
			errors.add(path + " must be less than " + render(maximum));
		}
	}

	private static void checkString(Map<String, Object> schema, String value, String path, List<String> errors) {
		int length = value.codePointCount(0, value.length());
		if (schema.get("minLength") instanceof Number min && length < min.intValue()) {
			errors.add(path + " must have at least " + min.intValue() + " characters");
		}
		if (schema.get("maxLength") instanceof Number max && length > max.intValue()) {
			errors.add(path + " must have at most " + max.intValue() + " characters");
		}
		if (schema.get("pattern") instanceof String pattern) {
			try {
				if (!Pattern.compile(pattern).matcher(value).find()) {
					errors.add(path + " must match " + pattern);
				}
			} catch (PatternSyntaxException e) {
				errors.add(path + " has an invalid pattern in its schema");
			}
		}
	}

	private static Object checkArray(Map<String, Object> schema, List<?> items, String path, List<String> errors) {
		if (schema.get("minItems") instanceof Number min && items.size() < min.intValue()) {
			errors.add(path + " must have at least " + min.intValue() + " items");
		}
		if (schema.get("maxItems") instanceof Number max && items.size() > max.intValue()) {
			errors.add(path + " must have at most " + max.intValue() + " items");
		}
		if (!(schema.get("items") instanceof Map<?, ?> itemSchema)) {
			return items;
		}
		List<Object> coerced = new ArrayList<>(items.size());
		for (int i = 0; i < items.size(); i++) {
			coerced.add(check(asSchema(itemSchema), items.get(i), path + "[" + i + "]", errors));
		}
		return coerced;
	}

	private static Object checkObject(Map<String, Object> schema, Map<?, ?> value, String path, List<String> errors) {
		Map<String, Object> properties = properties(schema);
		Map<String, Object> coerced = new LinkedHashMap<>();
		value.forEach((key, item) -> coerced.put(String.valueOf(key), item));
		for (Object name : list(schema.get("required"))) {
			if (coerced.get(String.valueOf(name)) == null) {
				errors.add(child(path, String.valueOf(name)) + " is required");
			}
		}
		Object additional = schema.get("additionalProperties");
		for (Map.Entry<String, Object> entry : coerced.entrySet()) {
			String name = entry.getKey();
			if (entry.getValue() == null) continue;
			if (properties.get(name) instanceof Map<?, ?> propertySchema) {
				entry.setValue(check(asSchema(propertySchema), entry.getValue(), child(path, name), errors));
			} else if (Boolean.FALSE.equals(additional)) {
				errors.add(child(path, name) + " is not a known parameter");
			} else if (additional instanceof Map<?, ?> additionalSchema) {
				entry.setValue(check(asSchema(additionalSchema), entry.getValue(), child(path, name), errors));
			}
		}
		return coerced;
	}

	private static void checkSchema(Map<String, Object> schema, String path, List<String> problems) {
		Object type = schema.get("type");
		if (type != null) {
			List<?> names = type instanceof List<?> list ? list : List.of(type);
			for (Object name : names) {
				if (!(name instanceof String text) || !TYPES.contains(text)) {
					problems.add(path + ".type has an unknown type " + render(name));
				}
			}
		}
		Object properties = schema.get("properties");
		if (properties != null) {
			if (properties instanceof Map<?, ?> map) {
				map.forEach((name, property) -> {
					if (property instanceof Map<?, ?> propertySchema) {
						checkSchema(asSchema(propertySchema), path + ".properties." + name, problems);
					} else if (!(property instanceof Boolean)) {
						problems.add(path + ".properties." + name + " is not a schema");
					}
				});
			} else {
				problems.add(path + ".properties is not an object");
			}
		}
		Object required = schema.get("required");
		if (required != null && !(required instanceof List<?> list && list.stream().allMatch(String.class::isInstance))) {
			problems.add(path + ".required is not a list of names");
		}
		Object items = schema.get("items");
		if (items instanceof Map<?, ?> map) {
			checkSchema(asSchema(map), path + ".items", problems);
		} else if (items != null && !(items instanceof Boolean) && !(items instanceof List<?>)) {
			problems.add(path + ".items is not a schema");
		}
		Object additional = schema.get("additionalProperties");
		if (additional instanceof Map<?, ?> map) {
			checkSchema(asSchema(map), path + ".additionalProperties", problems);
		} else if (additional != null && !(additional instanceof Boolean)) {
			problems.add(path + ".additionalProperties is neither a boolean nor a schema");
		}
		Object values = schema.get("enum");
		if (values != null && !(values instanceof List<?> list && !list.isEmpty())) {
			problems.add(path + ".enum is not a non-empty list");
		}
		for (String keyword : List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
			Object bound = schema.get(keyword);
			if (bound != null && !(bound instanceof Number)) problems.add(path + "." + keyword + " is not a number");
		}
		for (String keyword : List.of("exclusiveMinimum", "exclusiveMaximum")) {
			Object bound = schema.get(keyword);
			if (bound != null && !(bound instanceof Number) && !(bound instanceof Boolean)) {
				problems.add(path + "." + keyword + " is not a number");
			}
		}
		if (schema.get("pattern") != null) {
			if (schema.get("pattern") instanceof String pattern) {
				try {
					Pattern.compile(pattern);
				} catch (PatternSyntaxException e) {
					problems.add(path + ".pattern does not compile: " + e.getDescription());
				}
			} else {
				problems.add(path + ".pattern is not a string");
			}
		}
		for (String combinator : List.of("anyOf", "oneOf", "allOf")) {
			Object options = schema.get(combinator);
			if (options == null) continue;
			if (!(options instanceof List<?> list) || list.isEmpty()) {
				problems.add(path + "." + combinator + " is not a non-empty list");
				continue;
			}
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i) instanceof Map<?, ?> map) {
					checkSchema(asSchema(map), path + "." + combinator + "[" + i + "]", problems);
				} else {
					problems.add(path + "." + combinator + "[" + i + "] is not a schema");
				}
			}
		}
	}

	private static boolean matches(String type, Object value) {
		return switch (type) {
			case "string" -> value instanceof CharSequence;
			case "number" -> value instanceof Number;
			case "integer" -> value instanceof Integer || value instanceof Long
					|| (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
					&& Double.isFinite(number.doubleValue()));
			case "boolean" -> value instanceof Boolean;
			case "object" -> value instanceof Map<?, ?>;
			case "array" -> value instanceof List<?>;
			case "null" -> value == null;
			default -> true;
		};
	}

	private static boolean same(Object expected, Object actual) {
		if (expected instanceof Number left && actual instanceof Number right) {
			return left.doubleValue() == right.doubleValue();
		}
		return expected == null ? actual == null : expected.equals(actual);
	}

	private static List<String> types(Map<String, Object> schema) {
		Object type = schema.get("type");
		if (type instanceof String text) return List.of(text);
		if (type instanceof List<?> list) return list.stream().map(String::valueOf).toList();
		return List.of();
	}

	private static Map<String, Object> properties(Map<String, Object> schema) {
		return schema.get("properties") instanceof Map<?, ?> map ? asSchema(map) : Map.of();
	}

	private static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asSchema(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	private static String child(String path, String name) {
		return path.equals("params") ? name : path + "." + name;
	}

	private static String describe(List<String> types) {
		List<String> words = types.stream().map(type -> switch (type) {
			case "integer" -> "an integer";
			case "object" -> "an object";
			case "array" -> "a list";
			case "null" -> "null";
			default -> "a " + type;
		}).toList();
		return String.join(" or ", words);
	}

	private static String describeValue(Object value) {
		if (value == null) return "nothing";
		if (value instanceof CharSequence) return "text " + render(value);
		if (value instanceof Boolean) return "a boolean";
		if (value instanceof Number) return "the number " + render(value);
		if (value instanceof Map<?, ?>) return "an object";
		if (value instanceof Collection<?>) return "a list";
		return render(value);
	}

	private static String render(Object value) {
		if (value instanceof CharSequence text) return "\"" + text + "\"";
		if (value instanceof Double number && number == Math.rint(number) && Double.isFinite(number)) {
			return String.valueOf(number.longValue());
		}
		if (value instanceof List<?> list) return list.stream().map(CommandSchemas::render).toList().toString();
		return String.valueOf(value);
	}
}
