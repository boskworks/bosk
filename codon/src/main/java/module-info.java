/**
 * Bytecode disassembly and logging, built on the JDK {@link java.lang.classfile Class-File} API.
 * <p>
 * {@link works.bosk.codon.BytecodeDisassembler} turns a class file (or an already-parsed
 * {@link java.lang.classfile.ClassModel}) into a readable text listing: every instruction
 * with its bytecode index and its net effect on the operand stack, labels, branch targets,
 * exception handlers, and switch tables. The listing is designed to be logged via SLF4J
 * for troubleshooting generated code.
 */
module works.bosk.codon {
	requires transitive org.slf4j;

	requires static transitive org.jspecify;

	exports works.bosk.codon;
}
