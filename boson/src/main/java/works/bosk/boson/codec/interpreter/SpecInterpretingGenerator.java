package works.bosk.boson.codec.interpreter;

import java.io.PrintWriter;
import java.io.Writer;
import java.lang.invoke.WrongMethodTypeException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.codec.Generator;
import works.bosk.boson.exceptions.JsonException;
import works.bosk.boson.exceptions.JsonProcessingException;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.FixedObjectNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.RecognizedMember;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.EnclosingObject;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.MemberValue;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.Nullary;
import works.bosk.boson.mapping.spec.handles.TypedHandle;

import static works.bosk.boson.codec.Token.END_ARRAY;
import static works.bosk.boson.codec.Token.END_OBJECT;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.START_OBJECT;

public class SpecInterpretingGenerator implements Generator {
	private final JsonValueSpec spec;
	private final TypeMap typeMap;

	/**
	 * @param typeMap used to resolve {@link TypeRefNode}s
	 */
	public SpecInterpretingGenerator(JsonValueSpec spec, TypeMap typeMap) {
		this.typeMap = typeMap;
		this.spec = spec;
	}

	@Override
	public void generate(Writer out, Object value) {
		LOGGER.debug("Generating JSON for value of {} using spec {}", (value == null)? "null" : value.getClass(), spec);
		PrintWriter printStream;
		if (out instanceof PrintWriter pw) {
			printStream = pw;
		} else {
			printStream = new PrintWriter(out);
		}
		new Session(printStream, typeMap).generateAny(spec, value);
	}

	static final class Session {
		final PrintWriter out;
		final TypeMap typeMap;

		Session(PrintWriter out, TypeMap typeMap) {
			this.out = out;
			this.typeMap = typeMap;
		}

		public void generateAny(JsonValueSpec spec, Object value) {
			LOGGER.debug("Generate {} using {}", (value == null)? "null" : value.getClass().getSimpleName(), spec);
			try {
				switch (spec) {
					case BigNumberNode _,
						BooleanNode _,
						BoxedPrimitiveSpec _,
						PrimitiveNumberNode _ -> out.print(value);

					case EnumByNameNode _ -> generateEnumByName(value);
					case ArrayNode node -> generateArray(node, value);
					case MaybeNullSpec maybeNullSpec -> {
						if (value == null) {
							out.print("null");
						} else {
							generateAny(maybeNullSpec.child(), value);
						}
					}
					case UniformMapNode node -> generateUniformMap(node, value);
					case ParseCallbackSpec node -> generateAny(node.child(), value);
					case FixedObjectNode node -> generateFixedObject(node, value);
					case RepresentAsSpec node -> convertAndGenerate(node, value);
					case StringNode _ -> generateString(value);
					case TypeRefNode node -> generateAny(typeMap.get(node.type()), value);
				}
			} catch (JsonException e) {
				throw JsonException.wrap(e, spec.briefIdentifier() + ": ");
			}
		}

		private void convertAndGenerate(RepresentAsSpec node, Object value) {
			Object representation;
			try {
				representation = node.toRepresentation().handle().invoke(value);
			} catch (WrongMethodTypeException | ClassCastException e) {
				throw new JsonProcessingException(e);
			} catch (Throwable e) {
				throw new JsonProcessingException("Unexpected exception", e);
			}
			generateAny(node.representation(), representation);
		}

		private void generateEnumByName(Object value) {
			out.print(stringLiteral((((Enum<?>) value).name())));
		}

		private void generateArray(ArrayNode node, Object value) {
			var elementNode = node.elementNode();
			out.print(START_ARRAY.fixedRepresentation());
			String sep = "";

			Object iterator = node.emitter().start().invoke(value);
			while (node.emitter().hasNext().invoke(iterator).equals(Boolean.TRUE)) {
				out.print(sep);
				sep = ",";
				Object element = node.emitter().next().invoke(iterator);
				generateAny(elementNode, element);
			}
			out.print(END_ARRAY.fixedRepresentation());
		}

		private void generateUniformMap(UniformMapNode node, Object value) {
			// Unpack the handles
			TypedHandle start = node.emitter().start();
			TypedHandle hasNext = node.emitter().hasNext();
			TypedHandle next = node.emitter().next();
			TypedHandle getKey = node.emitter().getKey();
			TypedHandle getValue = node.emitter().getValue();

			out.print(START_OBJECT.fixedRepresentation());
			String sep = "";
			if (next.parameterTypes().size() == 1) {
				// Mutable iterator form
				Object iterator = start.invoke(value);
				while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
					var member = next.invoke(iterator);
					var memberKey = getKey.invoke(member);
					var memberValue = getValue.invoke(member);
					out.print(sep);
					sep = ",";
					generateAny(node.keyNode(), memberKey);
					out.print(":");
					generateAny(node.valueNode(), memberValue);
				}
			} else {
				// For-loop form
				assert next.parameterTypes().size() == 2;
				for (var iter = start.invoke(value);
					Boolean.TRUE.equals((hasNext.parameterTypes().size() == 1)
						? hasNext.invoke(iter)
						: hasNext.invoke(iter, value));
					iter = next.invoke(iter, value)
				) {
					var memberKey = (getKey.parameterTypes().size() == 1)
						? getKey.invoke(iter)
						: getKey.invoke(iter, value);
					var memberValue = (getValue.parameterTypes().size() == 1)
						? getValue.invoke(iter)
						: getValue.invoke(iter, value);
					out.print(sep);
					sep = ",";
					generateAny(node.keyNode(), memberKey);
					out.print(":");
					generateAny(node.valueNode(), memberValue);
				}
			}
			out.print(END_OBJECT.fixedRepresentation());

		}

		private void generateFixedObject(FixedObjectNode node, Object map) {
			LOGGER.debug("Generating fixed map for value of type {} using spec {}", map.getClass(), node);
			out.print("{");
			String sep = "";
			for (Map.Entry<String, RecognizedMember> entry : node.memberSpecs().entrySet()) {
				RecognizedMember member = entry.getValue();
				switch (member.valueSpec()) {
					case JsonValueSpec v -> {
						out.print(sep);
						sep = ",";
						generateFixedObjectMember(entry.getKey(), v, member.accessor().invoke(map));
					}
					case ComputedSpec _ -> {}
					case MaybeAbsentSpec(var v, _, var presenceCondition) -> {
						// TODO: Call the accessor at most once
						boolean isPresent = switch (presenceCondition) {
							case Nullary(var h) -> (boolean) h.invoke();
							case EnclosingObject(var h)-> (boolean) h.invoke(map);
							case MemberValue(var h) -> (boolean) h.invoke(member.accessor().invoke(map));
						};
						if (isPresent) {
							out.print(sep);
							sep = ",";
							generateFixedObjectMember(entry.getKey(), v, member.accessor().invoke(map));
						}
					}
				}
			}
			out.print("}");
		}

		private void generateFixedObjectMember(String componentName, JsonValueSpec valueSpec, Object value) {
			generateString(componentName);
			out.print(":");
			generateAny(valueSpec, value);
		}

		private void generateString(Object value) {
			out.print(stringLiteral(value.toString()));
		}

		static String stringLiteral(String s) {
			StringBuilder sb = new StringBuilder(s.length() + 2);
			sb.append('"');
			for (int i = 0; i < s.length(); ) {
				int cp = s.codePointAt(i);
				switch (cp) {
					case '"': sb.append("\\\""); break;
					case '\\': sb.append("\\\\"); break;
					case '\b': sb.append("\\b"); break;
					case '\f': sb.append("\\f"); break;
					case '\n': sb.append("\\n"); break;
					case '\r': sb.append("\\r"); break;
					case '\t': sb.append("\\t"); break;
					default:
						if (cp >= 0x20 && cp <= 0x7E) {
							sb.append((char) cp);
						} else {
							for (char c : Character.toChars(cp)) {
								sb.append(String.format("\\u%04x", (int) c));
							}
						}
				}
				i += Character.charCount(cp);
			}
			sb.append('"');
			return sb.toString();
		}


	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SpecInterpretingGenerator.class);
}
