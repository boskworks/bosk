package works.bosk.boson.codec.interpreter;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Parser;
import works.bosk.boson.codec.Token;
import works.bosk.boson.codec.io.SharedParserRuntime;
import works.bosk.boson.exceptions.JsonContentException;
import works.bosk.boson.exceptions.JsonProcessingException;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.RecognizedMember;
import works.bosk.boson.mapping.spec.FixedObjectNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.ScalarSpec;
import works.bosk.boson.mapping.spec.SpecNode;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.handles.ObjectAccumulator;
import works.bosk.boson.mapping.spec.handles.TypedHandle;

import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toSet;
import static works.bosk.boson.codec.Token.END_ARRAY;
import static works.bosk.boson.codec.Token.END_OBJECT;
import static works.bosk.boson.codec.Token.NULL;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.START_OBJECT;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.mapping.spec.PrimitiveNumberNode.PRIMITIVE_NUMBER_CLASSES;
import static works.bosk.boson.types.DataType.VOID;

/**
 * Parses JSON text according to a given {@link JsonValueSpec} tree.
 * <p>
 * Designed as an arena for experimentation rather than peak performance,
 * though performance shouldn't be <i>uselessly</i> poor,
 * so we take such steps as memoizing reflection info.
 */
public class SpecInterpretingParser implements Parser {
	final JsonValueSpec spec;
	final TypeMap typeMap;

	public SpecInterpretingParser(JsonValueSpec spec, TypeMap typeMap) {
		this.typeMap = typeMap;
		this.spec = spec;
	}

	@Override
	public Object parse(JsonReader json) throws IOException {
		return new InterpretedParseSession(json, typeMap).parseAny(spec);
	}

	/**
	 * A single parsing operation, consuming text from a given {@link JsonReader}.
	 */
	private static class InterpretedParseSession extends SharedParserRuntime {
		final TypeMap typeMap;

		private InterpretedParseSession(JsonReader input, TypeMap typeMap) {
			super(input);
			this.typeMap = typeMap;
		}

		private Object parseAny(JsonValueSpec node) throws IOException {
			if (typeMap.settings().iterative()) {
				return parseAny_iterative(node);
			} else {
				return parseAny_recursive(node);
			}
		}

		private Object parseAny_recursive(JsonValueSpec node) throws IOException {
			logEntry("parseAny_recursive", node);
			return switch (node) {
				case ScalarSpec n -> parseScalar(n);
				case ArrayNode n -> parseArray(n);
				case UniformMapNode n -> parseUniformMap(n);
				case MaybeNullSpec n -> parseMaybeNull(n);
				case ParseCallbackSpec n -> parseCallback(n);
				case FixedObjectNode n -> parseFixedObject(n);
				case RepresentAsSpec n -> parseAndConvert(n);
				case TypeRefNode n -> parseAny_recursive(typeMap.get(n.type()));
			};
		}

		private Object parseCallback(ParseCallbackSpec node) throws IOException {
			Object result;
			if (node.before().returnType() == VOID) {
				node.before().invoke();
				result = parseAny_recursive(node.child());
				node.after().invoke(result);
			} else {
				Object callbackContext = node.before().invoke();
				result = parseAny_recursive(node.child());
				node.after().invoke(callbackContext, result);
			}
			return result;
		}

		private Object parseAny_iterative(JsonValueSpec node) throws IOException {
			logEntry("parseAny_iterative", node);

			// This method allows us to parse documents with arbitrary nesting depth
			// with bounded stack space, but using heap space instead.
			// It is meant to inspire an efficient compiled parser that doesn't
			// rely on method calls to parse nested values.
			//
			// The compiled code would use gotos to jump to the appropriate logic
			// to parse each value, but since Java can't do arbitrary gotos like
			// bytecode can, we mimic that control flow using a loop containing
			// a switch over the node type. It's crucial that the compiler never
			// consults node objects, though (they're supposed to be compiled away!)
			// and so the nodes must be used only to direct control flow to the
			// appropriate case of the switch.
			//
			// Note that JIT compilers are likely to become a bit conservative
			// when given this kind of unstructured spaghetti code.
			// I wish we just had tail call elimination.
			// Also, this seems in danger of getting a lot of branch mispredictions.
			//
			// Some cases involve method calls, like parseScalar, just to keep the
			// code as clear as possible. These method calls cannot recurse,
			// so they don't defeat the goal of bounded stack space.
			// Compiled code should probably also use similar method calls if
			// possible, or else even moderately complex data structures could
			// exceed the 65535-byte limit on method bytecode size.
			// Those methods should not be processing JsonValueSpec objects, though,
			// and should instead be generated methods specialized to a particular JsonValueSpec.

			Deque<Accumulator> stack = new ArrayDeque<>();
			while (true) {
				Object resultValue;

				// The "begin" logic.
				// Given that we expect a value specified by `node`,
				// parse enough tokens to get to the next value we need to parse.
				// For a non-empty array, this will leave us at its first element;
				// for a non-empty object, this will leave us at its first member's value.
				//
				// For empty arrays and objects, we must parse the whole value;
				// this is not just an optimization, because each trip around the loop
				// assumes it will encounter a complete JSON value.

				LOGGER.debug("Beginning {}", node);
				switch (node) {
					case TypeRefNode n -> {
						// This is an oddball. In the compiler, we'd resolve these statically,
						// so there'd be no need to handle this case.
						node = typeMap.get(n.type());
						continue;
					}
					case ScalarSpec scalar -> resultValue = parseScalar(scalar);
					case MaybeNullSpec n -> {
						if (nextTokenIs(NULL)) {
							resultValue = null;
						} else {
							node = n.child();
							continue;
						}
					}
					case ArrayNode n -> {
						expect(START_ARRAY);
						if (nextTokenIs(END_ARRAY)) {
							var acc = n.accumulator().creator().invoke();
							resultValue = n.accumulator().finisher().invoke(acc);
						} else {
							stack.push(new ArrayAccumulator(n));
							node = n.elementNode();
							continue;
						}
					}
					case UniformMapNode n -> {
						expect(START_OBJECT);
						if (nextTokenIs(END_OBJECT)) {
							var acc = n.accumulator().creator().invoke();
							resultValue = n.accumulator().finisher().invoke(acc);
						} else {
							stack.push(new MapAccumulator(n, parseAny(n.keyNode())));
							node = n.valueNode();
							continue;
						}
					}
					case FixedObjectNode n -> {
						expect(START_OBJECT);
						if (nextTokenIs(END_OBJECT)) {
							resultValue = n.finisher().invoke(); // Finisher must have no args
						} else {
							String memberName = parseString();
							stack.push(new FixedObjectAccumulator(n, memberName));
							RecognizedMember member = requireNonNull(n.memberSpecs().get(memberName),
								"Unexpected member name [" + memberName + "]");
							node = switch (member.valueSpec()) {
								case JsonValueSpec j -> j; // The normal case: proceed with the member's value
								case MaybeAbsentSpec(var ifPresent, _, _) -> ifPresent; // It ain't absent
								case ComputedSpec _ -> {
									throw new JsonContentException("Invalid input: Unexpected value for computed member " + member.accessor());
								}
							};
							continue;
						}
					}
					case RepresentAsSpec n -> {
						node = n.representation();
						stack.push(new ConvertAccumulator(n.representation(), n.fromRepresentation().handle()));
						continue;
					}
					case ParseCallbackSpec n -> {
						if (n.before().returnType() == VOID) {
							n.before().invoke();
							stack.push(new CallbackAccumulator(n.child(), null, n.after()));
						} else {
							Object callbackContext = n.before().invoke();
							stack.push(new CallbackAccumulator(n.child(), callbackContext, n.after()));
						}
						node = n.child();
						continue;
					}
				}

				// The "finish" logic.
				// Having parsed a value, peek at the next token and see if we've reached
				// the end of a structured value. If so, we finish up that value;
				// then check if that, in turn, finishes a containing structured value,
				// and so on.
				// If we've finished the outermost structured value (ie. the entire
				// JSON document), return the result.

				assert resultValue != NO_RESULT;
				// TODO: There must be a simpler way to express this loop?
				while (true) {
					if (stack.isEmpty()) {
						return resultValue;
					}
					LOGGER.debug("Finishing {}", stack.getFirst().getClass().getSimpleName());
					resultValue = stack.getFirst().accumulate(resultValue);
					if (resultValue == NO_RESULT) {
						break;
					} else {
						// We finished a frame
						stack.pop();
					}
				}
				assert !stack.isEmpty(): "If we're continuing around the loop, we must be accumulating";

				// Here's the one final tricky bit that corresponds to a dynamic jump:
				// at this spot, we need to jump to a location in the code
				// that's determined by the top of the stack.
				// Being unconstrained by Java syntax, we could probably achieve this
				// with a switch statement right here, whose cases point directly
				// where we want to jump.
				//
				// To achieve this, we'll probably want a facility within the compiler
				// that allows it to "register" these "return point" jump targets and
				// associate small a small integer with each one; then store the jump
				// target index in the stack frame structure, and jump to it via a tableswitch.
				//
				// Sadly, this will probably be a rich source of branch misprediction stalls.

				node = stack.getFirst().valueSpec();
			}
		}

		/**
		 * Used to compose structured values like arrays and objects.
		 */
		interface Accumulator {
			JsonValueSpec valueSpec();

			/**
			 * @return null if still accumulating
			 */
			Object accumulate(Object value) throws IOException;
		}

		private class ArrayAccumulator implements Accumulator {
			private final ArrayNode n;
			private final Object accumulator;

			public ArrayAccumulator(ArrayNode n) {
				this.n = n;
				this.accumulator = n.accumulator().creator().invoke();
			}

			@Override
			public JsonValueSpec valueSpec() {
				return n.elementNode();
			}

			@Override
			public Object accumulate(Object value) throws IOException {
				n.accumulator().integrator().invoke(accumulator, value);
				if (InterpretedParseSession.this.nextTokenIs(END_ARRAY)) {
					return n.accumulator().finisher().invoke(accumulator);
				} else {
					return NO_RESULT;
				}
			}
		}

		private class MapAccumulator implements Accumulator {
			private final UniformMapNode n;
			private final Object accumulator;
			Object key;

			public MapAccumulator(UniformMapNode n, Object firstKey) {
				this.n = n;
				this.key = firstKey;
				this.accumulator = n.accumulator().creator().invoke();
			}

			@Override
			public JsonValueSpec valueSpec() {
				return n.valueNode();
			}

			@Override
			public Object accumulate(Object value) throws IOException {
				n.accumulator().integrator().invoke(accumulator, key, value);
				if (nextTokenIs(END_OBJECT)) {
					return n.accumulator().finisher().invoke(accumulator);
				} else {
					key = parseAny(n.keyNode());
					return NO_RESULT;
				}
			}
		}

		private class FixedObjectAccumulator implements Accumulator {
			private final FixedObjectNode n;
			private final Object[] ctorArgs;
			private final FixedObjectInfo objectInfo;
			private MemberInfo currentMember;

			public FixedObjectAccumulator(FixedObjectNode n, String firstMember) {
				this.n = n;
				this.ctorArgs = new Object[n.memberSpecs().size()];
				var iter = n.memberSpecs().values().iterator();
				for (int i = 0; i < ctorArgs.length; i++) {
					switch (iter.next().valueSpec()) {
						case ComputedSpec(var supplier) -> ctorArgs[i] = supplier.invoke();
						case MaybeAbsentSpec(_, ComputedSpec(var supplier), _) -> ctorArgs[i] = supplier.invoke();
						case JsonValueSpec _ -> {} // Leave as null for now
					}
				}
				this.objectInfo = objectInfo(n);
				this.currentMember = memberInfo(firstMember);
			}

			@Override
			public JsonValueSpec valueSpec() {
				// We know the current key is present because we just parsed it.
				// Absent key handling is not relevant here.
				return switch(currentMember.valueSpec()) {
					case ComputedSpec _ -> throw new JsonContentException("Invalid input: Unexpected value for computed member " + currentMember.index());
					case JsonValueSpec spec -> spec;
					case MaybeAbsentSpec(var ifPresent, _, _) -> ifPresent;
				};
			}

			@Override
			public Object accumulate(Object value) throws IOException {
				// In the compiled code, this would be storing the values in
				// a series of local variables. There would be a separate
				// accumulation snippet for each record component, and
				// we'd need to jump dynamically back to the right snippet
				// after parsing the component's value.
				ctorArgs[currentMember.index()] = value;
				if (nextTokenIs(END_OBJECT)) {
					return n.finisher().invoke(ctorArgs);
				} else {
					this.currentMember = memberInfo(parseString());
					return NO_RESULT;
				}
			}

			private MemberInfo memberInfo(String firstKey) {
				return requireNonNull(objectInfo.members().get(firstKey));
			}

		}

		private record ConvertAccumulator(
			JsonValueSpec representation,
			MethodHandle fromRepresentation
		) implements Accumulator {

			@Override
			public JsonValueSpec valueSpec() {
				return representation;
			}

			@Override
			public Object accumulate(Object value) throws IOException {
				try {
					return fromRepresentation.invoke(value);
				} catch (WrongMethodTypeException | ClassCastException e) {
					throw new JsonProcessingException(e);
				} catch (IOException e) {
					throw e;
				} catch (Throwable e) {
					throw new JsonProcessingException("Unexpected exception", e);
				}
			}
		}

		private record CallbackAccumulator (
			JsonValueSpec child,
			Object callbackContext,
			TypedHandle afterHook
		) implements Accumulator {

			@Override
			public JsonValueSpec valueSpec() {
				return child;
			}

			@Override
			public Object accumulate(Object value) {
				if (afterHook.parameterTypes().size() == 2) {
					afterHook.invoke(callbackContext, value);
				} else {
					assert afterHook.parameterTypes().size() == 1;
					afterHook.invoke(value);
				}
				return value;
			}
		}

		private Object parseScalar(ScalarSpec scalar) throws IOException {
			return switch (scalar) {
				case BigNumberNode _ -> parseBigNumber();
				case BooleanNode _ -> parseBoolean();
				case BoxedPrimitiveSpec(var child) -> parsePrimitiveNumber(child.targetClass());
				case EnumByNameNode n -> parseEnumByName(n);
				case PrimitiveNumberNode n -> parsePrimitiveNumber(n.targetClass());
				case StringNode _ -> parseString();
			};
		}

		private Object parseMaybeNull(MaybeNullSpec node) throws IOException {
			if (nextTokenIs(NULL)) {
				return null;
			} else {
				return parseAny(node.child());
			}
		}

		private Object parseAndConvert(RepresentAsSpec node) throws IOException {
			Object representation = parseAny(node.representation());
			try {
				return node.fromRepresentation().handle().invoke(representation);
			} catch (WrongMethodTypeException | ClassCastException e) {
				throw new JsonProcessingException(e);
			} catch (Throwable e) {
				throw new JsonProcessingException("Unexpected exception from RepresentAsSpec.fromRepresentation", e);
			}
		}

		private Object parseEnumByName(EnumByNameNode node) throws IOException {
			Class<? extends Enum<?>> enumType = node.enumType();
			logEntry("parseEnumByName", enumType);
			return parseEnumByName(valueOfHandle(enumType));
		}

		private Object parseArray(ArrayNode node) throws IOException {
			logEntry("parseArray", node);
			input.expectFixedToken(START_ARRAY);
			works.bosk.boson.mapping.spec.handles.ArrayAccumulator acc = node.accumulator();
			Object accumulator = acc.creator().invoke();
			while (input.peekValueToken() != END_ARRAY) {
				Object element = parseAny(node.elementNode());
				var returned = acc.integrator().invoke(accumulator, element);
				if (acc.integrator().returnType() != VOID) {
					accumulator = returned;
				}
			}
			input.consumeFixedToken(END_ARRAY);
			return acc.finisher().invoke(accumulator);
		}

		private Object parseUniformMap(UniformMapNode node) throws IOException {
			logEntry("parseUniformMap", node);
			input.expectFixedToken(START_OBJECT);
			ObjectAccumulator acc = node.accumulator();
			Object accumulator = acc.creator().invoke();
			while (input.peekValueToken() != END_OBJECT) {
				Object key = parseAny(node.keyNode());
				Object value = parseAny(node.valueNode());
				LOGGER.debug("| member [{}:{}]: |{}|", key, value, previewString());
				Object returned = acc.integrator().invoke(accumulator, key, value);
				if (acc.integrator().returnType() != VOID) {
					accumulator = returned;
				}
			}
			input.consumeFixedToken(END_OBJECT);
			return acc.finisher().invoke(accumulator);
		}

		private Object parsePrimitiveNumber(Class<?> targetClass) throws IOException {
			logEntry("parsePrimitiveNumber", targetClass);
			// This uses valueOf instead of parse. That makes this different from
			// the compiler, but the interpreter needs to box all numbers anyway,
			// so there's probably no significant difference here.
			Class<? extends Number> boxedType = PRIMITIVE_NUMBER_CLASSES.get(targetClass);
			return parsePrimitiveNumber(valueOfHandle(boxedType));
		}

		private Object parseFixedObject(FixedObjectNode node) throws IOException {
			logEntry("parseFixedObject", node);
			input.expectFixedToken(START_OBJECT);
			List<Object> memberValues = readMembers(node.memberSpecs());
			return node.finisher().invoke(memberValues.toArray());
		}

		private List<Object> readMembers(SequencedMap<String, RecognizedMember> componentsByName) throws IOException {
			Map<String, Object> memberValues = new HashMap<>();
			componentsByName.forEach((name, node) -> {
				switch (node.valueSpec()) {
					case MaybeAbsentSpec(_, ComputedSpec(var supplier), _) -> memberValues.put(name, supplier.invoke());
					case ComputedSpec(var supplier) -> memberValues.put(name, supplier.invoke());
					case JsonValueSpec _ -> {} // No default
				}
			});
			while (input.peekValueToken() != END_OBJECT) {
				String memberName = input.consumeString();
				var memberNode = componentsByName.get(memberName);
				if (memberNode == null) {
					throw new JsonContentException("Unexpected member name [" + memberName + "]");
				}
				LOGGER.debug("| member [{}:{}]: |{}|", memberName, memberNode, previewString());
				Object value = switch (memberNode.valueSpec()) {
					case JsonValueSpec n -> parseAny(n);
					case ComputedSpec _ -> throw new JsonContentException("Unexpected value for computed member [" + memberName + "]");
					case MaybeAbsentSpec(var n, _, _) -> parseAny(n);
				};
				memberValues.put(memberName, value);
			}
			input.consumeFixedToken(END_OBJECT);
			return componentsByName.keySet().stream().map(memberValues::get).toList();
		}

		private void logEntry(String methodName, SpecNode node) {
			LOGGER.debug("{}({}) @ {}: |{}|", methodName, node, input.currentOffset(), previewString());
			assertNodeIsApplicable(node);
		}

		private void assertNodeIsApplicable(SpecNode node) {
			assert nodeIsApplicable(node): "Node must be applicable "
				+ "@" + input.currentOffset()
				+ " |" + previewString() + "|"
				+ ": " + node;
		}

		private boolean nodeIsApplicable(SpecNode node) {
			if (node instanceof JsonValueSpec valueSpec) {
				Token nextToken = input.peekValueToken();
				Set<Token> expected = expectedTokens(valueSpec);
				boolean result = expected.contains(nextToken);
				LOGGER.trace("nodeIsApplicable: ({},{},{}) -> {}", node, expected, nextToken, result);
				return result;
			} else {
				// Besides JsonValueSpec, other specs don't correspond to JSON values,
				// so it doesn't matter what the next token is.
				return true;
			}
		}

		private Set<Token> expectedTokens(SpecNode node) {
			return switch (node) {
				case BigNumberNode _ -> Set.of(NUMBER);
				case BooleanNode _ -> Set.of(Token.TRUE, Token.FALSE);
				case BoxedPrimitiveSpec _ -> Set.of(NUMBER);
				case EnumByNameNode _ -> Set.of(STRING);
				case ArrayNode _ -> Set.of(START_ARRAY);
				case UniformMapNode _ -> Set.of(START_OBJECT);
				case MaybeNullSpec n -> Stream.of(expectedTokens(n.child()).stream(), Stream.of(NULL)).flatMap(identity()).collect(toSet());
				case ParseCallbackSpec n -> expectedTokens(n.child());
				case PrimitiveNumberNode _ -> Set.of(NUMBER);
				case FixedObjectNode _ -> Set.of(START_OBJECT);
				case RepresentAsSpec n -> expectedTokens(n.representation());
				case StringNode _ -> Set.of(STRING);
				case ComputedSpec _ -> EnumSet.allOf(Token.class);
				case MaybeAbsentSpec _ -> EnumSet.allOf(Token.class);
				case TypeRefNode n -> expectedTokens(typeMap.get(n.type()));
			};
		}

		/**
		 * A null value is a valid result, so we need to use something else
		 * to indicate that we don't have a result yet.
		 */
		private static final Object NO_RESULT = new Object();

	}

	private static FixedObjectInfo objectInfo(FixedObjectNode node) {
		Map<String, MemberInfo> members = new LinkedHashMap<>();
		node.memberSpecs().forEach((name, member) -> {
			members.put(name, new MemberInfo(members.size(), member.valueSpec()));
		});
		return new FixedObjectInfo(Map.copyOf(members));
	}

	record FixedObjectInfo(Map<String, MemberInfo> members){}
	record MemberInfo(int index, SpecNode valueSpec){}

	private static final Logger LOGGER = LoggerFactory.getLogger(SpecInterpretingParser.class);
}
