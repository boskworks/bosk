package works.bosk.dereferencers;

import java.lang.classfile.CodeBuilder;
import java.lang.reflect.Method;
import works.bosk.Reference;
import works.bosk.bytecode.Codegen;
import works.bosk.bytecode.Currier;
import works.bosk.bytecode.GeneratedClass;

import static java.lang.classfile.TypeKind.REFERENCE;
import static java.lang.reflect.AccessFlag.PUBLIC;

/**
 * The skeleton of a builder for {@link Dereferencer} objects. Creates a new class,
 * declares its methods, and calls a sequence of abstract methods to generate the
 * method bodies.
 * Provides protected utility methods that can be called to generate
 * the desired bytecodes for the method bodies.
 * The intent is that subclasses won't need to call {@link GeneratedClass} directly,
 * but can instead call the slightly simpler set of methods provided here.
 * (The only exception is {@link GeneratedClass#here}, which must be called directly
 * in order to return the correct stack information.)
 *
 * <p>
 * By "skeleton" here we really mean the GoF "Template Method" pattern inside
 * {@link #buildInstance()}}.
 */
abstract class SkeletonDereferencerBuilder implements DereferencerBuilder {
	private final String className;
	private final ClassLoader parentClassLoader;
	private final StackWalker.StackFrame sourceFileOrigin;
	protected final Currier currier = new Currier();

	public SkeletonDereferencerBuilder(String className, ClassLoader parentClassLoader, StackWalker.StackFrame sourceFileOrigin) {
		this.className = className;
		this.parentClassLoader = parentClassLoader;
		this.sourceFileOrigin = sourceFileOrigin;
	}

	protected abstract void generate_get(CodeBuilder codeBuilder);
	protected abstract void generate_with(CodeBuilder codeBuilder);
	protected abstract void generate_without(CodeBuilder codeBuilder);

	@Override
	public Dereferencer buildInstance() {
		return GeneratedClass.instantiate(className, DereferencerRuntime.class, parentClassLoader, sourceFileOrigin, currier, cb -> {
			cb.withMethodBody("get", GeneratedClass.mtd(Object.class, Object.class, Reference.class), PUBLIC.mask(), codeBuilder -> {
				lineInfo(codeBuilder);
				generate_get(codeBuilder);
				codeBuilder.areturn();
			});

			cb.withMethodBody("with", GeneratedClass.mtd(Object.class, Object.class, Reference.class, Object.class), PUBLIC.mask(), codeBuilder -> {
				lineInfo(codeBuilder);
				generate_with(codeBuilder);
				codeBuilder.areturn();
			});

			cb.withMethodBody("without", GeneratedClass.mtd(Object.class, Object.class, Reference.class), PUBLIC.mask(), codeBuilder -> {
				lineInfo(codeBuilder);
				generate_without(codeBuilder);
				codeBuilder.areturn();
			});
		});
	}

	/**
	 * Pushes the bosk root object onto the operand stack, and typecasts it to the given class.
	 */
	protected final void pushSourceObject(CodeBuilder codeBuilder, Class<?> expectedType) {
		codeBuilder.loadLocal(REFERENCE, codeBuilder.parameterSlot(0)); // Parameter 0 is the Dereferencer object itself
		castTo(codeBuilder, expectedType);
	}

	/**
	 * Pushes the {@link Reference} object onto the operand stack.
	 */
	protected final void pushReference(CodeBuilder codeBuilder) {
		codeBuilder.loadLocal(REFERENCE, codeBuilder.parameterSlot(1));
	}

	/**
	 * For {@link Dereferencer#with}, pushes the <code>newValue</code> object onto the operand stack,
	 * and typecasts it to the given class.
	 */
	protected final void pushNewValueObject(CodeBuilder codeBuilder, Class<?> expectedType) {
		codeBuilder.loadLocal(REFERENCE, codeBuilder.parameterSlot(2));
		castTo(codeBuilder, expectedType);
	}

	/**
	 * Pushes a copy of the top operand stack value.
	 */
	protected final void dup(CodeBuilder codeBuilder) { codeBuilder.dup(); }

	/**
	 * Reverses the order of the top two operands on the stack.
	 */
	protected final void swap(CodeBuilder codeBuilder) { codeBuilder.swap(); }

	/**
	 * Discards the top operand on the stack.
	 */
	protected final void pop(CodeBuilder codeBuilder) { codeBuilder.pop(); }

	/**
	 * Treats the top value on the stack as the given type.
	 */
	protected final void castTo(CodeBuilder codeBuilder, Class<?> expectedType) {
		Codegen.castTo(codeBuilder, expectedType);
	}

	/**
	 * Pushes the given value onto the operand stack.
	 */
	protected final void pushInt(CodeBuilder codeBuilder, int value) {
		codeBuilder.loadConstant(value);
	}

	/**
	 * Invokes the given method.
	 */
	protected final void invoke(CodeBuilder codeBuilder, Method method) {
		Codegen.invoke(codeBuilder, method);
	}

	/**
	 * Adds line number info pointing at the source of the generated instructions.
	 */
	protected final void lineInfo(CodeBuilder codeBuilder) {
		Codegen.lineInfo(codeBuilder, sourceFileOrigin);
	}

	/**
	 * Pushes the result of calling <code>reference.{@link Reference#idAt idAt}(segmentNum)</code>.
	 *
	 * <p>
	 * Equivalent to:
	 *
	 * <pre>
	 * pushReference();
	 * pushInt(segmentNum);
	 * invoke(REFERENCE_ID_AT);
	 * </pre>
	 */
	protected final void pushIdAt(CodeBuilder codeBuilder, int segmentNum) {
		pushReference(codeBuilder);
		pushInt(codeBuilder, segmentNum);
		invoke(codeBuilder, REFERENCE_ID_AT);
	}

	static {
		try {
			REFERENCE_ID_AT = Reference.class.getDeclaredMethod("idAt", int.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private final static Method REFERENCE_ID_AT;
}
