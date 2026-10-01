## codon

This is the subproject for the published `codon` bytecode disassembly library.
It is not a part of the bosk framework per se, and can be used separately.

The library turns a class file (or an already-parsed `java.lang.classfile.ClassModel`)
into a readable text listing of its bytecode, built on the JDK's Class-File API.
The listing is designed to be logged with SLF4J for troubleshooting code that is
generated at runtime: each instruction shows its bytecode index and its net effect
on the operand stack, and control flow, exception handlers, switch tables, and
invokedynamic bootstrap methods are rendered with resolved label names.

See the [javadocs](https://javadoc.io/doc/works.bosk/codon/latest/works.bosk.codon/module-summary.html) for more information.
