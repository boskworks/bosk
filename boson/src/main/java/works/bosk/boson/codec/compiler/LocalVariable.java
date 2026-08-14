package works.bosk.boson.codec.compiler;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;

/**
 * A local variable slot in the method being built, allocated via
 * {@link CodeBuilder#allocateLocal}.
 */
public record LocalVariable(TypeKind typeKind, int firstSlot) {
	public void load(CodeBuilder cb) {
		cb.loadLocal(typeKind, firstSlot);
	}

	public void store(CodeBuilder cb) {
		cb.storeLocal(typeKind, firstSlot);
	}
}
