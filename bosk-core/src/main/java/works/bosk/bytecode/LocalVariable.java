package works.bosk.bytecode;

import java.lang.classfile.TypeKind;

public record LocalVariable(
	TypeKind type,
	int slot
) { }
