package com.zqnt.sdk.edge.support;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain Java values to and from {@link Struct}, without a JSON round trip. */
public final class Structs {

	private Structs() {
	}

	public static Struct toStruct(Map<String, ?> values) {
		Struct.Builder builder = Struct.newBuilder();
		if (values != null) {
			values.forEach((key, value) -> builder.putFields(key, toValue(value)));
		}
		return builder.build();
	}

	public static Value toValue(Object value) {
		Value.Builder builder = Value.newBuilder();
		if (value == null) return builder.setNullValue(NullValue.NULL_VALUE).build();
		if (value instanceof Value protoValue) return protoValue;
		if (value instanceof Boolean bool) return builder.setBoolValue(bool).build();
		if (value instanceof Number number) return builder.setNumberValue(number.doubleValue()).build();
		if (value instanceof CharSequence text) return builder.setStringValue(text.toString()).build();
		if (value instanceof Enum<?> constant) return builder.setStringValue(constant.name()).build();
		if (value instanceof Map<?, ?> map) {
			Struct.Builder struct = Struct.newBuilder();
			map.forEach((key, item) -> struct.putFields(String.valueOf(key), toValue(item)));
			return builder.setStructValue(struct).build();
		}
		if (value instanceof Collection<?> items) {
			ListValue.Builder list = ListValue.newBuilder();
			items.forEach(item -> list.addValues(toValue(item)));
			return builder.setListValue(list).build();
		}
		if (value instanceof Object[] items) {
			ListValue.Builder list = ListValue.newBuilder();
			for (Object item : items) list.addValues(toValue(item));
			return builder.setListValue(list).build();
		}
		return builder.setStringValue(String.valueOf(value)).build();
	}

	/** Numbers come back as {@link Double}, as the Struct holds them. */
	public static Map<String, Object> toMap(Struct struct) {
		Map<String, Object> map = new LinkedHashMap<>();
		if (struct != null) {
			struct.getFieldsMap().forEach((key, value) -> map.put(key, fromValue(value)));
		}
		return map;
	}

	public static Object fromValue(Value value) {
		return switch (value.getKindCase()) {
			case NULL_VALUE, KIND_NOT_SET -> null;
			case NUMBER_VALUE -> value.getNumberValue();
			case STRING_VALUE -> value.getStringValue();
			case BOOL_VALUE -> value.getBoolValue();
			case STRUCT_VALUE -> toMap(value.getStructValue());
			case LIST_VALUE -> {
				List<Object> list = new ArrayList<>();
				value.getListValue().getValuesList().forEach(item -> list.add(fromValue(item)));
				yield list;
			}
		};
	}
}
