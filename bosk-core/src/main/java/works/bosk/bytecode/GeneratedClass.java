package works.bosk.bytecode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import works.bosk.codon.BytecodeDisassembler;
import works.bosk.exceptions.NotYetImplementedException;

import static java.lang.classfile.TypeKind.REFERENCE;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.util.stream.Collectors.joining;

/**
 * The shared machinery for generating, loading, and instantiating a class at
 * runtime. The generated class extends or implements the given <code>supertype</code>
 * and implements the methods declared by {@code body}.
 * <p>
 * The {@code body} describes the class's methods by calling
 * {@link java.lang.classfile.ClassBuilder#withMethodBody} and related builder
 * methods; it emits instructions through the {@link java.lang.classfile.CodeBuilder}
 * handed to each method body. Objects that the generated code needs to reference
 * are <em>curried</em>: {@link Currier#pushCurried} registers them during emission,
 * and they end up in static final fields populated by the generated class's static
 * initializer.
 */
public final class GeneratedClass {

	/**
	 * Builds a class, verifies it, loads it with a fresh {@link ClassLoader}, and instantiates it.
	 *
	 * @param className The simple name of the generated class;
	 * 		the actual name will be given the prefix <code>GENERATED_</code> to identify it as not corresponding to any source file
	 * @param supertype A superclass or interface for the generated class to inherit
	 * @param parentClassLoader The classloader that should be used as the parent of the one we'll use
	 * 		to load the newly-compiled class.
	 * @param sourceFileOrigin Indicates the package in which the generated class should reside, and
	 * 		the source file to which all debug line number information should refer.
	 * @param currier Receives the objects the generated code needs to reference
	 * @param body Describes the generated class's methods
	 */
	public static <T> T instantiate(
		String className,
		Class<? extends T> supertype,
		ClassLoader parentClassLoader,
		StackWalker.StackFrame sourceFileOrigin,
		Currier currier,
		Consumer<java.lang.classfile.ClassBuilder> body
	) {
		String dottyName = dottyName(sourceFileOrigin, className);
		ClassDesc self = ClassDesc.of(dottyName);
		byte[] bytes = ClassFile.of().build(self, classBuilder -> {
			ClassDesc superClass;
			classBuilder.withFlags(PUBLIC, FINAL);
			if (supertype.isInterface()) {
				superClass = cd(Object.class);
				classBuilder.withSuperclass(superClass);
				classBuilder.withInterfaceSymbols(cd(supertype));
			} else {
				superClass = cd(supertype);
				classBuilder.withSuperclass(superClass);
			}
			classBuilder.accept(SourceFileAttribute.of(sourceFileOrigin.getFileName()));

			// The generated class holds its curried values in static fields, so all
			// it needs is a no-arg constructor that calls the superclass's.
			classBuilder.withMethod("<init>", mtd(void.class), PUBLIC.mask(), mb -> mb.withCode(cb -> {
				cb.loadLocal(REFERENCE, cb.receiverSlot());
				cb.invokespecial(superClass, "<init>", mtd(void.class));
				cb.return_();
			}));

			currier.withOwner(self);
			body.accept(classBuilder);

			if (!currier.isEmpty()) {
				long curryKey = BytecodeRuntime.curry(currier.valueArray());
				currier.emitStaticsAndClinit(classBuilder, curryKey);
			}
		});
		if (LOGGER.isTraceEnabled()) {
			BytecodeDisassembler.log(LOGGER, Level.TRACE, bytes);
		}
		if (VERIFY_BYTECODE) {
			verify(bytes);
		}
		if (DUMP_BYTECODE_TO_FILE) {
			try (FileOutputStream out = new FileOutputStream("out.class")) {
				out.write(bytes);
			} catch (IOException e) {
				throw new NotYetImplementedException(e);
			}
		}
		try {
			Constructor<?> ctor = new CustomClassLoader(parentClassLoader).loadThemBytes(dottyName, bytes).getConstructor();
			return supertype.cast(ctor.newInstance());
		} catch (NoSuchMethodException | InstantiationException | IllegalAccessException | VerifyError | InvocationTargetException e) {
			throw new AssertionError("Should be able to instantiate the generated class", e);
		}
	}

	/**
	 * Captures the location of the caller, for passing as a <code>sourceFileOrigin</code>.
	 */
	public static StackWalker.StackFrame here() {
		return StackWalker.getInstance().walk(frames -> frames
			.skip(1)
			.findFirst()
			.orElseThrow());
	}

	public static ClassDesc cd(Class<?> c) {
		return c.describeConstable().get();
	}

	public static MethodTypeDesc mtd(Class<?> returnType, Class<?>... parameterTypes) {
		return MethodTypeDesc.of(cd(returnType), Stream.of(parameterTypes).map(GeneratedClass::cd).toArray(ClassDesc[]::new));
	}

	private static String dottyName(StackWalker.StackFrame sourceFileOrigin, String className) {
		String sourceDottyName = sourceFileOrigin.getClassName();
		return sourceDottyName.substring(0, sourceDottyName.lastIndexOf('.')) + ".GENERATED_" + className;
	}

	/**
	 * Best-effort verification of the generated bytecode, for early feedback
	 * when a generator has a bug. The JVM's verifier is the authoritative check;
	 * if the {@link ClassFile} API is unable to verify for any reason, we defer
	 * to the JVM.
	 */
	private static void verify(byte[] bytes) {
		List<VerifyError> errors;
		try {
			errors = ClassFile.of().verify(bytes);
		} catch (RuntimeException e) {
			LOGGER.debug("Unable to verify generated class", e);
			return;
		}
		if (!errors.isEmpty()) {
			String message = errors.stream().map(Throwable::getMessage).collect(
				joining("\n\t", "Generated class failed verification:\n\t", ""));
			throw new AssertionError(message, errors.getFirst());
		}
	}

	private static final class CustomClassLoader extends ClassLoader {
		CustomClassLoader(ClassLoader parentClassLoader) {
			super(parentClassLoader);
		}

		public Class<?> loadThemBytes(String dottyName, byte[] b) {
			return defineClass(dottyName, b, 0, b.length);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedClass.class);
	private static final boolean DUMP_BYTECODE_TO_FILE = false;

	/**
	 * The {@link ClassFile} API's verifier gives better error messages than the
	 * JVM's own verifier, but it's best-effort and slower. Enable it when assertions
	 * are on (as they are for tests), so bugs surface with helpful diagnostics.
	 */
	private static final boolean VERIFY_BYTECODE = GeneratedClass.class.desiredAssertionStatus();

	private GeneratedClass() {}
}
