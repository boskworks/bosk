package works.bosk.boson.codec.compiler;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.StackWalker.StackFrame;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.codec.Codec;
import works.bosk.boson.codec.Generator;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Parser;
import works.bosk.boson.codec.Token;
import works.bosk.boson.codec.compiler.LocalVariableAllocator.LocalVariable;
import works.bosk.boson.codec.interpreter.SpecInterpretingGenerator;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.SpecNode;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.PrimitiveType;

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;
import static java.lang.classfile.Opcode.IFEQ;
import static java.lang.classfile.TypeKind.REFERENCE;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static works.bosk.boson.codec.Token.END_ARRAY;
import static works.bosk.boson.codec.Token.END_OBJECT;
import static works.bosk.boson.codec.Token.NULL;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.START_OBJECT;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.io.SharedParserRuntime.PRIMITIVE_PARSE_METHOD_NAMES;
import static works.bosk.boson.mapping.spec.PrimitiveNumberNode.PRIMITIVE_NUMBER_CLASSES;

/**
 * <em>Maintenance node</em>: currently compiles only parsers, not generators.
 * Compiling generators should be straightforward,
 * so we've focused initially on parsers since they are more awkward,
 * and hence served as a more stringent test of our abstractions.
 * But generators would also benefit from compilation,
 * so we should implement that at some point.
 */
public class SpecCompiler {
	final TypeMap typeMap;
	final String className;
	static final Path tempDir;

	static {
		try {
			tempDir = Files.createTempDirectory("SpecCompiler_");
			tempDir.toFile().deleteOnExit();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public SpecCompiler(TypeMap typeMap) {
		this.typeMap = typeMap;
		this.className = "GeneratedCodec_" + CLASS_COUNTER.incrementAndGet();
	}

	/**
	 * @param extraNodes nodes that may or may not be present in {@link #typeMap}
	 *                     that should also have parse methods generated.
	 */
	public Codec compile(JsonValueSpec... extraNodes) {
		LOGGER.info("Compiling {}", typeMap.settings());
		var currier = new Currier();
		byte[] bytecode = ClassFile.of()
			.build(ClassDesc.of(className), classBuilder -> {
				classBuilder.withFlags(PUBLIC, FINAL);
				classBuilder.withSuperclass(cd(CompiledParserRuntime.class));
				classBuilder.accept(SourceFileAttribute.of(thisFrame(0).getFileName()));

				Set<JsonValueSpec> specsToEmit = new HashSet<>(typeMap.knownSpecs());
				specsToEmit.addAll(List.of(extraNodes));
				specsToEmit.forEach(node -> emitParseMethod(classBuilder, node, currier));

				// Auto-generate nullable versions of known types
				typeMap.knownTypes().forEach(t -> {
					if (t instanceof KnownType kt && !kt.rawClass().isPrimitive()) {
						emitParseMethod(
							classBuilder,
							new MaybeNullSpec(new TypeRefNode(kt)),
							currier);
					}
				});

				classBuilder.withMethod("<init>",
					MethodTypeDesc.of(VOID, cd(JsonReader.class)),
					PUBLIC.mask(),
					mb -> mb.withCode(cb -> {
						cb.loadLocal(REFERENCE, 0);
						cb.loadLocal(REFERENCE, 1);
						lineInfo(cb);
						cb.invokespecial(
							classBuilder.constantPool().methodRefEntry(
								cd(CompiledParserRuntime.class),
								"<init>",
								MethodTypeDesc.of(VOID, cd(JsonReader.class))
							)
						);
						cb.return_();
					})
				);

				currier.curried.forEach(cv ->
					classBuilder.withField(
						cv.completeFieldName(),
						cv.type(),
						fb -> fb.withFlags(PRIVATE, STATIC, FINAL)
					)
				);

				long curryKey = CompiledParserRuntime.curry(currier.valueArray());
				classBuilder.withMethod("<clinit>",
					MethodTypeDesc.of(VOID),
					PUBLIC.mask() | STATIC.mask(),
					mb -> mb.withCode(cb -> {
						cb.loadConstant(curryKey);
						cb.invokestatic(
							cb.constantPool().methodRefEntry(
								cd(CompiledParserRuntime.class),
								"claimCurriedArray",
								MethodTypeDesc.of(cd(Object.class).arrayType(), long.class.describeConstable().get())
							)
						);

						currier._initializeStatics(cb, ClassDesc.of(className));
						cb.return_();
					})
				);
			});

		if (LOGGER.isInfoEnabled()) {
			Path bytecodeFile = tempDir.resolve(className + ".class");
			LOGGER.info("Writing bytecode to {}", bytecodeFile);
			try (var out = new FileOutputStream(bytecodeFile.toFile())) {
				out.write(bytecode);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		var classLoader = new OneOffClassLoader();
		var generatedClass = classLoader.defineClass(className, bytecode);

		MethodHandle ctor;
		try {
			ctor = MethodHandles.lookup().findConstructor(generatedClass, MethodType.methodType(void.class, JsonReader.class));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to instantiate the generated Codec class", e);
		}
		return new Codec() {
			@Override
			public Parser parserFor(JsonValueSpec spec) {
				if (spec instanceof TypeRefNode(var type)) {
					JsonValueSpec referencedSpec = typeMap.get(type);
					if (referencedSpec == null) {
						throw new IllegalArgumentException("Cannot parse type " + type);
					}
					return parserFor(referencedSpec);
				}
				MethodRef parseMethodRef = PARSE_METHODS_BY_NODE.get(spec);
				if (parseMethodRef == null && spec instanceof MaybeNullSpec(var child)) {
					// We auto-generate MaybeNullSpecs whose child is a TypeRefNode,
					// so try that before giving up.
					parseMethodRef = PARSE_METHODS_BY_NODE.get(new MaybeNullSpec(new TypeRefNode(child.dataType())));
				}
				if (parseMethodRef == null) {
					throw new IllegalArgumentException("Codec cannot parse spec: " + spec);
				}
				MethodHandle parseMH;
				try {
					parseMH = MethodHandles.lookup().findVirtual(generatedClass, parseMethodRef.name, parseMethodRef.type().resolveConstantDesc(MethodHandles.lookup()));
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Unexpected error getting MethodHandle for " + parseMethodRef.name, e);
				}
				return json -> {
					try {
						CompiledParserRuntime parserRuntime = (CompiledParserRuntime) ctor.invoke(json);
						return parseMH.invoke(parserRuntime);
					} catch (Throwable e) {
						throw new IllegalStateException("wat", e);
					}
				};
			}

			@Override
			public Generator generatorFor(JsonValueSpec spec) {
				// TODO: Compile these
				return new SpecInterpretingGenerator(spec, typeMap);
			}
		};
	}

	private void lineInfo(CodeBuilder cb) {
		cb.lineNumber(thisFrame(1).getLineNumber());
	}

	private void lineInfo(CodeBuilder cb, int skip) {
		cb.lineNumber(thisFrame(1+skip).getLineNumber());
	}

	/**
	 * @param skip how many extra frames to skip. 0 reports line info from the immediate caller.
	 */
	private StackFrame thisFrame(int skip) {
		return StackWalker.getInstance(RETAIN_CLASS_REFERENCE).walk(s ->
			s.limit(2+skip).skip(1+skip).findFirst().get());
	}

	private void _autoBox(CodeBuilder cb, PrimitiveType p) {
		Class<?> boxed;
		if (PrimitiveType.BOOLEAN.equals(p)) {
			boxed = Boolean.class;
		} else {
			boxed = PRIMITIVE_NUMBER_CLASSES.get(p.rawClass());
		}
		lineInfo(cb);
		cb.invokestatic(cb.constantPool().methodRefEntry(
			cd(boxed),
			"valueOf",
			mtd(boxed, p.rawClass())
		));
	}


	/**
	 * Generates a method that parses a specific {@link JsonValueSpec}.
	 * These act as the "entry points" for parsing.
	 */
	private void emitParseMethod(ClassBuilder classBuilder, JsonValueSpec spec, Currier currier) {
		var m = getParseMethod(spec);
		classBuilder.withMethod(
			m.name(),
			m.type(),
			m.accessFlagMask(),
			mb -> mb.withCode(cb -> {
				new ParserCodeBuilder(classBuilder, mb, cb, currier)
					._parseAny(spec);
				cb.return_(nodeReturnTypeKind(spec));
			})
		);
	}

	private MethodRef getParseMethod(SpecNode spec) {
		return PARSE_METHODS_BY_NODE.computeIfAbsent(spec, this::computeParseMethod);
	}

	private MethodRef computeParseMethod(SpecNode valueSpec) {
		var returnType = valueSpec.dataType();
		return new MethodRef(
			"parse_" + valueSpec.briefIdentifier() + "_" + PARSE_METHODS_BY_NODE.size(),
			ClassDesc.of(className),
			mtd(ParserCodeBuilder.sanitized(returnType.leastUpperBoundClass())),
			Set.of(PUBLIC, FINAL));
	}

	private final Map<SpecNode, MethodRef> PARSE_METHODS_BY_NODE = new ConcurrentHashMap<>();

	record MethodRef(String name, ClassDesc owner, MethodTypeDesc type, Set<AccessFlag> accessFlags) {
		int accessFlagMask() {
			return accessFlags.stream().mapToInt(AccessFlag::mask).reduce(0, (a, b) -> a | b);
		}
	}

	public TypeKind nodeReturnTypeKind(SpecNode node) {
		return TypeKind.fromDescriptor(node.dataType().leastUpperBoundClass().descriptorString());
	}

	record CurriedValue(
		String name,
		Object value,
		ClassDesc type,
		int index
	) {
		/**
		 * Guaranteed unique within its containing class
		 */
		String completeFieldName() {
			return name + "_" + index;
		}

		void _load(CodeBuilder cb, ClassDesc owner) {
			cb.getstatic(owner, completeFieldName(), type);
		}

		/**
		 * Assumes the value to be stored is on top of the operand stack
		 */
		void _store(CodeBuilder cb, ClassDesc owner) {
			cb.putstatic(owner, completeFieldName(), type);
		}
	}

	/**
	 * Lets you reference objects in generated code by stashing them in static final fields of the generated class.
	 */
	static class Currier {
		final List<CurriedValue> curried = new ArrayList<>();

		public CurriedValue curry(String name, Object value, ClassDesc type) {
			CurriedValue curriedValue = new CurriedValue(name, value, type, curried.size());
			curried.add(curriedValue);
			return curriedValue;
		}

		public Object[] valueArray() {
			return curried.stream().map(CurriedValue::value).toArray();
		}

		/**
		 * Assumes an array of curried values is on top of the operand stack.
		 *
		 * @param owner the class that contains the static fields
		 */
		public void _initializeStatics(CodeBuilder cb, ClassDesc owner) {
			for (var cv: curried) {
				cb.dup();
				cb.loadConstant(cv.index());
				cb.aaload(); // TODO: primitives?
				cb.checkcast(cv.type());
				cv._store(cb, owner);
			}
			cb.pop(); // We're done with the array
		}
	}

	/**
	 * Methods starting with underscore are named after the bytecode they emit,
	 * not what they actually do.
	 * The {@code _parseXxx} methods leave the resulting value on the operand stack.
	 */
	class ParserCodeBuilder {
		final ClassBuilder classBuilder;
		final MethodBuilder methodBuilder;
		final CodeBuilder codeBuilder;
		final Currier currier;
		final LocalVariableAllocator localVariableAllocator = new LocalVariableAllocator(1);

		ParserCodeBuilder(
			ClassBuilder classBuilder,
			MethodBuilder methodBuilder,
			CodeBuilder codeBuilder,
			Currier currier
		) {
			this.classBuilder = classBuilder;
			this.methodBuilder = methodBuilder;
			this.codeBuilder = codeBuilder;
			this.currier = currier;
		}

		private void _parseAny(JsonValueSpec n) {
			switch (n) {
				case BigNumberNode node -> _parseBigNumber(node);
				case BooleanNode _ -> _parseBoolean();
				case BoxedPrimitiveSpec node -> _parseBoxedPrimitive(node);
				case EnumByNameNode node -> _parseEnumByName(node);
				case ArrayNode node -> _parseArray(node);
				case UniformMapNode node -> _parseUniformMap(node);
				case MaybeNullSpec node -> _parseMaybeNull(node);
				case ParseCallbackSpec node -> _parseCallBack(node);
				case PrimitiveNumberNode node -> _parsePrimitiveNumber(node);
				case FixedMapNode node -> _parseFixedMap(node);
				case RepresentAsSpec node -> _parseAndConvert(node);
				case StringNode _ -> _parseString();
				case TypeRefNode node -> _parseTypeRef(node);
			}
		}

		private void _parseBoxedPrimitive(BoxedPrimitiveSpec node) {
			var child = node.child();
			_parsePrimitiveNumber(child);
			Class<? extends Number> boxedType = PRIMITIVE_NUMBER_CLASSES.get(child.targetClass());
			String valueOfMethodName = "valueOf";
			// Call the appropriate parse method
			lineInfo(codeBuilder);
			codeBuilder.invokestatic(
				cd(boxedType),
				valueOfMethodName,
				mtd(node.targetClass(), child.targetClass())
			);
		}

		private void _parseCallBack(ParseCallbackSpec node) {
			// We've written this in a slightly awkward style because both the
			// parsed value and the callback context can be any datatype, including
			// 2-slot values (and even 0-slot for the callback context!).
			// Writing it in the following way makes the datatypes all work out.

			try (var locals = localVariableAllocator.newScope()) {
				TypeKind returnKind = nodeReturnTypeKind(node);
				LocalVariable result = locals.allocate(returnKind);

				// Get this MH in the right place on the call stack before things get hairy
				var afterType = curryAndLoad(node.after().handle(), "after");

				// Call `before` leaving its result on the stack
				var beforeType = curryAndLoad(node.before().handle(), "before");
				_invokeExact(beforeType);

				// Parse and store the result so we can use it twice
				_parseAny(node.child());
				result.store(codeBuilder);

				// At this stage, the operand stack already has the `after` handle and the callback context value if any.
				// The third argument to the `after` handle is the parsed object
				result.load(codeBuilder);
				_invokeExact(afterType);

				// The result of this whole process is the parsed object
				result.load(codeBuilder);
			}
		}

		private void _parseComputed(ComputedSpec node) {
			MethodHandle supplier = node.supplier().handle();
			var mt = curryAndLoad(supplier, supplier.type().returnType().getSimpleName() + "_supplier");
			_invokeExact(mt);
		}

		private void _parseBigNumber(BigNumberNode node) {
			assert node.numberClass().equals(BigDecimal.class);
			_loadRuntime();
			lineInfo(codeBuilder);
			_callRuntime(Number.class, "parseBigNumber");
			codeBuilder.checkcast(cd(node.numberClass()));
		}

		private void _parseBoolean() {
			_loadRuntime();
			lineInfo(codeBuilder);
			_callRuntime(boolean.class, "parseBoolean");
		}

		private void _parseEnumByName(EnumByNameNode node) {
			// TODO: Use trie to avoid reading the whole name?
			MethodType type = MethodType.methodType(node.enumType(), String.class);
			MethodHandle valueOf;
			try {
				valueOf = typeMap.lookupFor(node.enumType()).findStatic(node.enumType(), "valueOf", type);
			} catch (NoSuchMethodException | IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			var mt = curryAndLoad(valueOf, node.enumType().getSimpleName() + "_valueOf");
			_parseString();
			_invokeExact(mt);
		}

		private void _parseArray(ArrayNode node) {
//			codeBuilder.dup();

			var acc = node.accumulator();
			try (var locals = localVariableAllocator.newScope()) {
				// Allocate labels
				Label loop = codeBuilder.newLabel();
				Label element = codeBuilder.newLabel();
				Label endArray = codeBuilder.newLabel();
				Label error = codeBuilder.newLabel();

				_skipToken(START_ARRAY);

				LocalVariable accumulator = locals.allocate(REFERENCE);
				_invokeExact(curryAndLoad(acc.creator().handle(), "acc_creator"));
				accumulator.store(codeBuilder);

				codeBuilder.labelBinding(loop);
				_peekTokenOrdinal();
				codeBuilder.lookupswitch(element,
					List.of(
						SwitchCase.of(END_ARRAY.ordinal(), endArray)
					)
				);

				codeBuilder.labelBinding(element);
				var integratorType = curryAndLoad(acc.integrator().handle(), "acc_integrator");
				accumulator.load(codeBuilder);
				_parseAny(node.elementNode());
				_invokeExact(integratorType);
				if (integratorType.returnType() != void.class) {
					accumulator.store(codeBuilder);
				}
				codeBuilder.goto_w(loop);

				codeBuilder.labelBinding(error);
				_throwParseError("Unexpected character");

				codeBuilder.labelBinding(endArray);
				_skipToken(END_ARRAY);

				var finisherType = curryAndLoad(acc.finisher().handle(), "acc_finisher");
				accumulator.load(codeBuilder);
				_invokeExact(finisherType);

			}
		}

		private void _parseUniformMap(UniformMapNode node) {
			var acc = node.accumulator();
			try (var locals = localVariableAllocator.newScope()) {
				// Allocate labels
				Label loop = codeBuilder.newLabel();
				Label member = codeBuilder.newLabel();
				Label endObject = codeBuilder.newLabel();
				Label error = codeBuilder.newLabel();

				_skipToken(START_OBJECT);

				LocalVariable accumulator = locals.allocate(nodeReturnTypeKind(node));
				_invokeExact(curryAndLoad(acc.creator().handle(), "acc_creator"));
				accumulator.store(codeBuilder);

				codeBuilder.labelBinding(loop);
				_peekTokenOrdinal();
				codeBuilder.lookupswitch(error,
					List.of(
						SwitchCase.of(STRING.ordinal(), member),
						SwitchCase.of(END_OBJECT.ordinal(), endObject)
					)
				);

				codeBuilder.labelBinding(member);
				var integratorType = curryAndLoad(acc.integrator().handle(), "acc_integrator");
				accumulator.load(codeBuilder);
				_parseAny(node.keyNode());
				_parseAny(node.valueNode());
				_invokeExact(integratorType);
				if (integratorType.returnType() != void.class) {
					accumulator.store(codeBuilder);
				}
				codeBuilder.goto_w(loop);

				codeBuilder.labelBinding(error);
				_throwParseError("Unexpected character");

				codeBuilder.labelBinding(endObject);
				_skipToken(END_OBJECT);

				var finisherType = curryAndLoad(acc.finisher().handle(), "acc_finisher");
				accumulator.load(codeBuilder);
				_invokeExact(finisherType);
			}
		}

		private void _parseMaybeNull(MaybeNullSpec node) {
			Label done = codeBuilder.newLabel();
			_peekTokenOrdinal();
			codeBuilder.loadConstant(NULL.ordinal());
			codeBuilder.isub();
			codeBuilder.ifThen(IFEQ, block-> {
				_skipToken(NULL);
				block.aconst_null();
				block.goto_w(done);
			});
			_parseAny(node.child());
			var type = node.child().dataType();
			if (type instanceof PrimitiveType p) {
				_autoBox(codeBuilder, p);
			}
			codeBuilder.labelBinding(done);
		}

		private void _parseAndConvert(RepresentAsSpec node) {
			MethodHandle fromHandle = node.fromRepresentation().handle();
			var mt = curryAndLoad(fromHandle, "from" + fromHandle.type().parameterType(0).getSimpleName());
			_parseAny(node.representation());
			_invokeExact(mt);
		}

		private MethodType curryAndLoad(MethodHandle object, String name) {
			MethodHandle sanitized = sanitized(object);
			currier
				.curry(name, sanitized, MethodHandle.class.describeConstable().get())
				._load(codeBuilder, ClassDesc.of(className));
			return sanitized.type();
		}

		/**
		 * @return a version of {@code givenHandle} with a different signature composed
		 * only of types that are accessible from the generated code.
		 */
		private static MethodHandle sanitized(MethodHandle givenHandle) {
			return givenHandle.asType(sanitized(givenHandle.type()));
		}

		/**
		 * @return a version of {@code givenType} with all object types replaced with {@link Object}
		 * in case they're not actually accessible from the generated bytecode.
		 */
		private static MethodType sanitized(MethodType givenType) {
			return MethodType.methodType(
				sanitized(givenType.returnType()),
				Arrays.stream(givenType.parameterArray())
					.map(ParserCodeBuilder::sanitized)
					.toArray(Class<?>[]::new)
			);
		}

		private static Class<?> sanitized(Class<?> givenClass) {
			// TODO: We can do better here. Anything accessible from the generated
			// code can be left alone, and that will result in fewer checkcasts.
			if (givenClass.isPrimitive()) {
				return givenClass;
			} else {
				return Object.class;
			}
		}

		private void _invokeExact(MethodType type) {
			lineInfo(codeBuilder, 1);
			codeBuilder.invokevirtual(
				MethodHandle.class.describeConstable().get(),
				"invokeExact",
				type.describeConstable().get()
			);
		}

		private void _parsePrimitiveNumber(PrimitiveNumberNode node) {
			Class<? extends Number> boxedType = PRIMITIVE_NUMBER_CLASSES.get(node.targetClass());
			String parseMethodName = PRIMITIVE_PARSE_METHOD_NAMES.get(node.targetClass());

			// Read the number as a string
			_readNumberAsCharSequence();
			lineInfo(codeBuilder);
			codeBuilder.invokeinterface(
				cd(CharSequence.class),
				"toString",
				mtd(String.class)
			);

			// Call the appropriate parse method
			lineInfo(codeBuilder);
			codeBuilder.invokestatic(
				cd(boxedType),
				parseMethodName,
				mtd(node.targetClass(), String.class)
			);
		}

		private void _parseFixedMap(FixedMapNode fixedMapNode) {
			LOGGER.debug("_parseFixedMap on:\n{}", fixedMapNode);
			TrieNode trie = TrieNode.from(fixedMapNode.memberSpecs().keySet());
			LOGGER.debug(" -> trie: {}", trie);
//			codeBuilder.dup(); // This causes a stack underflow that makes the classfile API dump the bytecode

			try (var locals = localVariableAllocator.newScope()) {
				// Allocate labels
				Label loop = codeBuilder.newLabel();
				Label member = codeBuilder.newLabel();
				Label endObject = codeBuilder.newLabel();
				Label error = codeBuilder.newLabel();

				// Allocate locals
				Map<String, LocalVariable> componentLocalsByName = new LinkedHashMap<>(); // ORDER MATTERS
				fixedMapNode.memberSpecs().forEach((name, componentNode) -> {
					TypeKind typeKind = nodeReturnTypeKind(componentNode.valueSpec());
					LocalVariable v = locals.allocate(typeKind);
					componentLocalsByName.put(name, v);

					// Initialize with default value
					switch (componentNode.valueSpec()) {
						case ComputedSpec s -> _parseComputed(s);
						case MaybeAbsentSpec(_, var s, _) -> _parseComputed(s);
						default -> _loadDefault(typeKind);
					}
					v.store(codeBuilder);
				});

				_skipToken(START_OBJECT);

				codeBuilder.labelBinding(loop);
				_peekTokenOrdinal();
				codeBuilder.lookupswitch(error,
					List.of(
						SwitchCase.of(STRING.ordinal(), member),
						SwitchCase.of(END_OBJECT.ordinal(), endObject)
					)
				);

				codeBuilder.labelBinding(member);
				_startConsumingString();
				generateCodePointSwitch(trie, fixedMapNode, componentLocalsByName, loop, error);

				codeBuilder.labelBinding(error);
				_throwParseError("Unexpected character; was expecting one of " + fixedMapNode.memberSpecs().keySet());

				codeBuilder.labelBinding(endObject);
				_skipToken(END_OBJECT);

				// All the local variables should have their values now.
				// Time to call the finisher
				var mt = curryAndLoad(fixedMapNode.finisher().handle(), "fixedMap_finisher");
				fixedMapNode.memberSpecs().forEach((name, node) -> {
					var local = componentLocalsByName.get(name);
					local.load(codeBuilder);
					if (local.typeKind() == REFERENCE) {
						Class<?> expectedType = node.dataType().leastUpperBoundClass();
						LOGGER.trace("typeKind is {} for {}", local.typeKind(), expectedType);
						codeBuilder.checkcast(cd(expectedType));
					}
				});
				_invokeExact(mt);
			}
		}

		private void _loadDefault(TypeKind typeKind) {
			switch (typeKind.asLoadable()) {
				case INT -> codeBuilder.iconst_0();
				case LONG -> codeBuilder.lconst_0();
				case FLOAT -> codeBuilder.fconst_0();
				case DOUBLE -> codeBuilder.dconst_0();
				case REFERENCE -> codeBuilder.aconst_null();
				default -> throw new IllegalStateException("Unexpected typeKind: " + typeKind);
			}
		}

		/**
		 * Recursively a nest of switches to match the strings described by the given trie.
		 */
		private void generateCodePointSwitch(TrieNode node, FixedMapNode fixedMapNode, Map<String, LocalVariable> componentLocalsByName, Label loop, Label error) {
			LOGGER.debug("generateCodePointSwitch({})", node);
			switch (node) {
				case TrieNode.LeafNode(String memberName, int matchedPrefix) -> {
					_skipToEnd(memberName.length() - matchedPrefix);
					var child = fixedMapNode.memberSpecs().get(memberName);
					LOGGER.debug("-> leaf({})", child);
					switch (child.valueSpec()) {
						case JsonValueSpec v -> {
							_parseAny(v);
							componentLocalsByName.get(memberName).store(codeBuilder);
						}
						case MaybeAbsentSpec(var v, _, _) -> {
							_parseAny(v);
							componentLocalsByName.get(memberName).store(codeBuilder);
						}
						case ComputedSpec _ -> {
							_throwParseError("Unexpected value for computed member [" + memberName + "]");
						}
					}
					codeBuilder.goto_w(loop);
				}
				case TrieNode.ChoiceNode(var edges) -> {
					// Recursively build the cases
					if (edges.size() == 1 && typeMap.settings().fewerSwitches()) {
						int numSkips = 1;
						var child = edges.getFirst().child();
						while (child instanceof TrieNode.ChoiceNode(var childEdges) && childEdges.size() == 1) {
							++numSkips;
							child = childEdges.getFirst().child();
						}
						LOGGER.debug("-> skip({})", numSkips);
						_skipStringChars(numSkips);
						generateCodePointSwitch(child, fixedMapNode, componentLocalsByName, loop, error);
					} else {
						LOGGER.debug("-> switch({})", edges.stream().map(TrieEdge::codePoint).toList());
						_nextStringChar();
						var cases = edges.stream()
							.map(e -> SwitchCase.of(e.codePoint(), codeBuilder.newLabel()))
							.toList();
						codeBuilder.lookupswitch(error, cases);
						Iterator<TrieEdge> iter = edges.iterator();
						cases.forEach(c -> {
							codeBuilder.labelBinding(c.target());
							generateCodePointSwitch(iter.next().child(), fixedMapNode, componentLocalsByName, loop, error);
						});
					}
				}
			}
		}

		private void _startConsumingString() {
			_loadRuntime();
			lineInfo(codeBuilder, 1);
			_callRuntime(void.class, "startConsumingString");
		}

		private void _nextStringChar() {
			_loadRuntime();
			lineInfo(codeBuilder, 1);
			_callRuntime(int.class, "nextStringChar");
		}

		private void _skipStringChars(int numCharsToSkip) {
			_loadRuntime();
			codeBuilder.loadConstant(numCharsToSkip);
			lineInfo(codeBuilder, 1);
			_callRuntime(void.class, "skipStringChars", int.class);
		}

		private void _skipToEnd(int remainingLength) {
			_loadRuntime();
			codeBuilder.loadConstant(remainingLength);
			lineInfo(codeBuilder, 1);
			_callRuntime(void.class, "skipToEndOfString", int.class);
		}

		private void _skipToken(Token token) {
			_loadRuntime();
			codeBuilder.loadConstant(token.ordinal());
			lineInfo(codeBuilder, 1);
			_callRuntime("skipTokenWithOrdinal", int.class);
		}

		private void _parseString() {
			_loadRuntime();
			lineInfo(codeBuilder);
			_callRuntime(String.class, "parseString");
		}

		private void _readNumberAsCharSequence() {
			_loadRuntime();
			lineInfo(codeBuilder, 1);
			_callRuntime(CharSequence.class, "readNumberAsCharSequence");
		}

		private void _throwParseError(String message) {
			_loadRuntime();
			codeBuilder.loadConstant(codeBuilder.constantPool().stringEntry(message).constantValue());
			lineInfo(codeBuilder, 1);
			_callRuntime("parseError", String.class);
		}

		private void _parseTypeRef(TypeRefNode node) {
			lineInfo(codeBuilder, 1);
			_invokeVirtual(getParseMethod(typeMap.get(node.type())));
		}

		private void _invokeVirtual(MethodRef mr) {
			_loadRuntime();
			codeBuilder.invokevirtual(classBuilder.constantPool().methodRefEntry(
				mr.owner(),
				mr.name(),
				mr.type()
			));
		}

		private void _loadRuntime() {
			codeBuilder.loadLocal(REFERENCE, 0);
		}

		private void _peekTokenOrdinal() {
			_loadRuntime();
			lineInfo(codeBuilder);
			_callRuntime(int.class, "peekTokenOrdinal");
		}

		private void _callRuntime(Class<?> returnType, String methodName, Class<?>... parameterTypes) {
			codeBuilder.invokevirtual(classBuilder.constantPool().methodRefEntry(
				cd(CompiledParserRuntime.class),
				methodName,
				mtd(returnType, parameterTypes))
			);
		}

		private void _callRuntime(String methodName, Class<?>... parameterTypes) {
			codeBuilder.invokevirtual(classBuilder.constantPool().methodRefEntry(
				cd(CompiledParserRuntime.class),
				methodName,
				mtd(VOID, parameterTypes))
			);
		}

	}

	static class OneOffClassLoader extends ClassLoader {
		public Class<?> defineClass(String name, byte[] b) {
			return defineClass(name, b, 0, b.length);
		}
	}

	static ClassDesc cd(Class<?> c) {
		return c.describeConstable().get();
	}

	static MethodTypeDesc mtd(Class<?> returnType, Class<?>... parameterTypes) {
		return MethodTypeDesc.of(cd(returnType), Stream.of(parameterTypes).map(SpecCompiler::cd).toArray(ClassDesc[]::new));
	}

	static MethodTypeDesc mtd(ClassDesc returnType, Class<?>... parameterTypes) {
		return MethodTypeDesc.of(returnType, Stream.of(parameterTypes).map(SpecCompiler::cd).toArray(ClassDesc[]::new));
	}

	static final ClassDesc VOID = ClassDesc.ofDescriptor("V");

	private static final AtomicLong CLASS_COUNTER = new AtomicLong(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(SpecCompiler.class);
}
