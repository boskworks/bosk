package works.bosk.bytecode;

/**
 * An object reference that should be passed to the constructor of a generated
 * class so that it can be referenced from method bodies via
 * {@link org.objectweb.asm.Opcodes#GETFIELD GETFIELD}.
 *
 * @param slot The parameter slot in which this will arrive in the constructor
 */
public record CurriedField(
	int slot,
	String name,
	String typeDescriptor,
	Object value
) { }
