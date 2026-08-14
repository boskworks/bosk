package works.bosk.bytecode;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;

import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;

/**
 * Collects the objects that generated code needs to reference (its <em>curried
 * values</em>) and turns them into static final fields of the generated class.
 * <p>
 * During method emission, {@link #pushCurried} both registers the value (deduplicating
 * by identity) and emits the <code>getstatic</code> that pushes it. Once all methods
 * have been emitted, {@link GeneratedClass} calls {@link #emitStaticsAndClinit} to
 * declare the fields and a static initializer that claims the values from
 * {@link BytecodeRuntime}.
 */
public final class Currier {
	private final List<CurriedValue> values = new ArrayList<>();
	private ClassDesc owner;

	/**
	 * Emits code to push the given object on the operand stack, registering it
	 * as a curried value if it hasn't been already.
	 *
	 * @param name purely descriptive; doesn't need to be unique
	 * @param type the static type of the value (because the dynamic type might not
	 *             be accessible from the generated class)
	 */
	public void pushCurried(CodeBuilder cb, String name, Object value, Class<?> type) {
		String fieldName = curry(name, value, type);
		cb.getstatic(owner, fieldName, GeneratedClass.cd(type));
	}

	private String curry(String name, Object value, Class<?> type) {
		type.cast(value);
		for (CurriedValue candidate: values) {
			if (candidate.value() == value) {
				return candidate.fieldName();
			}
		}
		String fieldName = "CURRIED" + values.size() + "_" + name;
		values.add(new CurriedValue(fieldName, GeneratedClass.cd(type), value));
		return fieldName;
	}

	Object[] valueArray() {
		return values.stream().map(CurriedValue::value).toArray();
	}

	boolean isEmpty() {
		return values.isEmpty();
	}

	void withOwner(ClassDesc owner) {
		this.owner = owner;
	}

	/**
	 * Declares a static final field per curried value, populated by a static
	 * initializer that claims the values from {@link BytecodeRuntime}.
	 */
	void emitStaticsAndClinit(java.lang.classfile.ClassBuilder classBuilder, long curryKey) {
		for (CurriedValue curriedValue: values) {
			classBuilder.withField(curriedValue.fieldName(), curriedValue.type(),
				fb -> fb.withFlags(PRIVATE, STATIC, FINAL));
		}
		classBuilder.withMethod("<clinit>", GeneratedClass.mtd(void.class), PUBLIC.mask() | STATIC.mask(),
			mb -> mb.withCode(cb -> {
				cb.loadConstant(curryKey);
				cb.invokestatic(cb.constantPool().methodRefEntry(
					GeneratedClass.cd(BytecodeRuntime.class),
					"claimCurriedArray",
					GeneratedClass.mtd(Object[].class, long.class)
				));
				for (int i = 0; i < values.size(); i++) {
					CurriedValue curriedValue = values.get(i);
					cb.dup();
					cb.loadConstant(i);
					cb.aaload();
					cb.checkcast(curriedValue.type());
					cb.putstatic(owner, curriedValue.fieldName(), curriedValue.type());
				}
				cb.pop();
				cb.return_();
			}));
	}

	private record CurriedValue(String fieldName, ClassDesc type, Object value) { }
}
