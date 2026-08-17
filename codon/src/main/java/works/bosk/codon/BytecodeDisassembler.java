package works.bosk.codon;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.BootstrapMethodEntry;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.BootstrapMethodsAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

import static java.util.stream.Collectors.joining;

/**
 * Turns a class file into a readable text listing of its bytecode, for
 * troubleshooting classes that are generated at runtime.
 * <p>
 * Each instruction is rendered on its own line, prefixed by its bytecode index
 * and its net effect on the operand stack, in slots. Control flow is made
 * navigable by numbering labels and rendering branch targets as those numbers.
 * Exception handlers, switch tables, and invokedynamic bootstrap methods are
 * rendered inline, resolved to the same label numbers.
 */
public final class BytecodeDisassembler {

	/**
	 * Renders the disassembly of the class contained in the given class file bytes.
	 */
	public static String disassemble(byte[] classFile) {
		return disassemble(ClassFile.of().parse(classFile));
	}

	/**
	 * Renders the disassembly of the given class.
	 */
	public static String disassemble(ClassModel model) {
		StringBuilder sb = new StringBuilder();
		renderAnnotations(sb, model, "");
		sb.append("class ").append(model.thisClass().asSymbol().displayName());
		model.superclass().ifPresent(superclass ->
			sb.append(" extends ").append(superclass.asSymbol().displayName()));
		if (!model.interfaces().isEmpty()) {
			sb.append(" implements ").append(
				model.interfaces().stream().map(i -> i.asSymbol().displayName()).collect(joining(", ")));
		}
		sb.append('\n');
		model.findAttribute(Attributes.sourceFile()).ifPresent(attribute ->
			sb.append("  source: ").append(attribute.sourceFile().stringValue()).append('\n'));
		for (FieldModel field : model.fields()) {
			renderField(sb, field);
		}
		for (MethodModel method : model.methods()) {
			renderMethod(sb, method);
		}
		model.findAttribute(Attributes.bootstrapMethods()).ifPresent(attribute ->
			renderBootstrapMethods(sb, attribute));
		return sb.toString();
	}

	/**
	 * Renders the disassembly of the given method, including its annotations
	 * and its code.
	 */
	public static String disassemble(MethodModel method) {
		StringBuilder sb = new StringBuilder();
		renderMethod(sb, method);
		return sb.toString();
	}

	/**
	 * Renders the disassembly of the given code.
	 */
	public static String disassemble(CodeModel code) {
		StringBuilder sb = new StringBuilder();
		code.parent().ifPresent(method ->
			sb.append("== ").append(method.methodName().stringValue()).append(method.methodType().stringValue()).append(" ==\n"));
		renderCode(sb, code);
		return sb.toString();
	}

	/**
	 * Logs the disassembly of the class contained in the given class file bytes, at the given level.
	 * <p>
	 * The disassembly is always computed, even if the level is not enabled; callers who would
	 * rather not pay that cost can guard the call with the appropriate {@code isXxxEnabled} check.
	 */
	public static void log(Logger logger, org.slf4j.event.Level level, byte[] classFile) {
		logger.atLevel(level).log(disassemble(classFile));
	}

	/**
	 * Logs the disassembly of the given class, at the given level.
	 * <p>
	 * The disassembly is always computed, even if the level is not enabled; callers who would
	 * rather not pay that cost can guard the call with the appropriate {@code isXxxEnabled} check.
	 */
	public static void log(Logger logger, org.slf4j.event.Level level, ClassModel model) {
		logger.atLevel(level).log(disassemble(model));
	}

	private static void renderField(StringBuilder sb, FieldModel field) {
		renderAnnotations(sb, field, "  ");
		sb.append("  ").append(modifiers(field.flags()))
			.append(field.fieldTypeSymbol().displayName()).append(' ')
			.append(field.fieldName().stringValue()).append(";\n");
	}

	private static String modifiers(AccessFlags flags) {
		StringBuilder sb = new StringBuilder();
		if (flags.has(AccessFlag.PUBLIC)) {
			sb.append("public ");
		}
		if (flags.has(AccessFlag.PRIVATE)) {
			sb.append("private ");
		}
		if (flags.has(AccessFlag.PROTECTED)) {
			sb.append("protected ");
		}
		if (flags.has(AccessFlag.STATIC)) {
			sb.append("static ");
		}
		if (flags.has(AccessFlag.FINAL)) {
			sb.append("final ");
		}
		if (flags.has(AccessFlag.VOLATILE)) {
			sb.append("volatile ");
		}
		if (flags.has(AccessFlag.TRANSIENT)) {
			sb.append("transient ");
		}
		return sb.toString();
	}

	private static void renderMethod(StringBuilder sb, MethodModel method) {
		renderAnnotations(sb, method, "");
		sb.append("== ").append(method.methodName().stringValue()).append(method.methodType().stringValue()).append(" ==\n");
		if (method.code().isPresent()) {
			renderCode(sb, method.code().get());
		} else {
			sb.append("  (no code)\n");
		}
	}

	private static void renderCode(StringBuilder sb, CodeModel code) {
		List<java.lang.classfile.CodeElement> elements = code.elementStream().toList();
		Map<Label, Integer> labelIndex = new IdentityHashMap<>();
		Map<Label, List<ExceptionCatch>> catchesByHandler = new IdentityHashMap<>();
		int bci = 0;
		for (var element : elements) {
			if (element instanceof Label label) {
				labelIndex.put(label, labelIndex.size() + 1);
			} else if (element instanceof Instruction instruction) {
				bci += instruction.sizeInBytes();
			} else if (element instanceof ExceptionCatch exceptionCatch) {
				catchesByHandler.computeIfAbsent(exceptionCatch.handler(), k -> new ArrayList<>()).add(exceptionCatch);
			}
		}
		int runningBci = 0;
		for (int i = 0; i < elements.size(); i++) {
			var element = elements.get(i);
			if (element instanceof Label label) {
				if (hasFollowingInstruction(elements, i)) {
					sb.append("L").append(labelIndex.get(label)).append(":\n");
					List<ExceptionCatch> catches = catchesByHandler.get(label);
					if (catches != null) {
						for (ExceptionCatch exceptionCatch : catches) {
							renderCatch(sb, exceptionCatch, labelIndex);
						}
					}
				}
			} else if (element instanceof LineNumber lineNumber) {
				sb.append("       ; line ").append(lineNumber.line()).append('\n');
			} else if (element instanceof Instruction instruction) {
				renderInstruction(sb, instruction, runningBci, labelIndex);
				runningBci += instruction.sizeInBytes();
			}
			// Other elements (LocalVariable debug info, etc.) are not rendered
		}
	}

	/**
	 * A label that is not followed by any instruction is the compiler's marker for
	 * the end of the method; it has nothing to point at, so it is not rendered.
	 */
	private static boolean hasFollowingInstruction(List<java.lang.classfile.CodeElement> elements, int fromIndex) {
		for (int i = fromIndex + 1; i < elements.size(); i++) {
			if (elements.get(i) instanceof Instruction) {
				return true;
			}
		}
		return false;
	}

	private static void renderCatch(StringBuilder sb, ExceptionCatch exceptionCatch, Map<Label, Integer> labelIndex) {
		sb.append("catch ");
		exceptionCatch.catchType().ifPresentOrElse(
			type -> sb.append(type.name().stringValue()),
			() -> sb.append("any"));
		sb.append(" (L").append(labelIndex.get(exceptionCatch.tryStart()))
			.append("..L").append(labelIndex.get(exceptionCatch.tryEnd()))
			.append("):\n");
	}

	private static void renderInstruction(StringBuilder sb, Instruction instruction, int bci, Map<Label, Integer> labelIndex) {
		sb.append(String.format("%6d:%+3d: %s", bci, StackEffects.net(instruction), instruction.opcode().name().toLowerCase()));
		if (instruction instanceof TableSwitchInstruction switchInstruction) {
			renderTableSwitch(sb, switchInstruction, bci, labelIndex);
		} else if (instruction instanceof LookupSwitchInstruction switchInstruction) {
			renderLookupSwitch(sb, switchInstruction, bci, labelIndex);
		} else {
			renderOperands(sb, instruction, labelIndex);
			sb.append('\n');
		}
	}

	private static void renderTableSwitch(StringBuilder sb, TableSwitchInstruction switchInstruction, int bci, Map<Label, Integer> labelIndex) {
		sb.append(' ').append(switchInstruction.lowValue()).append("..").append(switchInstruction.highValue()).append('\n');
		int defaultBci = defaultBci(bci);
		appendSwitchEntry(sb, defaultBci, "default", labelIndex.get(switchInstruction.defaultTarget()));
		int entryBci = defaultBci + 12;
		for (SwitchCase switchCase : switchInstruction.cases()) {
			appendSwitchEntry(sb, entryBci, "case " + switchCase.caseValue(), labelIndex.get(switchCase.target()));
			entryBci += 4;
		}
	}

	private static void renderLookupSwitch(StringBuilder sb, LookupSwitchInstruction switchInstruction, int bci, Map<Label, Integer> labelIndex) {
		sb.append('\n');
		int defaultBci = defaultBci(bci);
		appendSwitchEntry(sb, defaultBci, "default", labelIndex.get(switchInstruction.defaultTarget()));
		int entryBci = defaultBci + 8;
		for (SwitchCase switchCase : switchInstruction.cases()) {
			appendSwitchEntry(sb, entryBci, "case " + switchCase.caseValue(), labelIndex.get(switchCase.target()));
			entryBci += 8;
		}
	}

	/**
	 * The bytecode index of the first four-byte field of a switch instruction
	 * that begins at the given bci, per the JVM spec's alignment rule.
	 */
	private static int defaultBci(int switchBci) {
		return switchBci + 1 + (4 - ((switchBci + 1) % 4)) % 4;
	}

	private static void appendSwitchEntry(StringBuilder sb, int bci, String what, int label) {
		sb.append(String.format("%6d:", bci)).append(SWITCH_ENTRY_INDENT).append(what).append(" -> L").append(label).append('\n');
	}

	private static void renderOperands(StringBuilder sb, Instruction instruction, Map<Label, Integer> labelIndex) {
		if (instruction instanceof ConstantInstruction.IntrinsicConstantInstruction) {
			// The mnemonic already encodes the constant
		} else if (instruction instanceof ConstantInstruction.ArgumentConstantInstruction constant) {
			sb.append(' ').append(constant.constantValue());
		} else if (instruction instanceof ConstantInstruction.LoadConstantInstruction constant) {
			sb.append(' ').append(renderConstant(constant.constantValue()));
		} else if (instruction instanceof IncrementInstruction increment) {
			sb.append(' ').append(increment.slot()).append(", ").append(increment.constant());
		} else if (instruction instanceof LoadInstruction load) {
			appendLocalSlot(sb, load.opcode(), load.slot());
		} else if (instruction instanceof StoreInstruction store) {
			appendLocalSlot(sb, store.opcode(), store.slot());
		} else if (instruction instanceof FieldInstruction field) {
			var fieldRef = field.field();
			sb.append(' ').append(fieldRef.owner().name().stringValue()).append('.')
				.append(fieldRef.name().stringValue()).append(':').append(fieldRef.type().stringValue());
		} else if (instruction instanceof InvokeInstruction invoke) {
			var methodRef = invoke.method();
			sb.append(' ').append(methodRef.owner().name().stringValue()).append('.')
				.append(methodRef.name().stringValue()).append(':').append(methodRef.type().stringValue());
		} else if (instruction instanceof InvokeDynamicInstruction indy) {
			sb.append(' ').append(indy.name().stringValue()).append(':').append(indy.typeSymbol().descriptorString()).append('\n');
			sb.append(BOOTSTRAP_INDENT).append("bootstrap B").append(indy.invokedynamic().bootstrap().bsmIndex())
				.append(": ").append(indy.bootstrapMethod().owner().displayName())
				.append("::").append(indy.bootstrapMethod().methodName());
		} else if (instruction instanceof BranchInstruction branch) {
			sb.append(" L").append(labelIndex.get(branch.target()));
		} else if (instruction instanceof TypeCheckInstruction typeCheck) {
			sb.append(' ').append(typeCheck.type().name().stringValue());
		} else if (instruction instanceof NewObjectInstruction newObject) {
			sb.append(' ').append(newObject.className().name().stringValue());
		} else if (instruction instanceof NewReferenceArrayInstruction newReferenceArray) {
			sb.append(' ').append(newReferenceArray.componentType().name().stringValue());
		} else if (instruction instanceof NewPrimitiveArrayInstruction newPrimitiveArray) {
			sb.append(' ').append(newPrimitiveArray.typeKind().name().toLowerCase());
		} else if (instruction instanceof NewMultiArrayInstruction newMultiArray) {
			sb.append(' ').append(newMultiArray.arrayType().name().stringValue()).append(" dims=").append(newMultiArray.dimensions());
		}
		// Other instructions (returns, throws, arithmetic, etc.) take no operands
	}

	private static void appendLocalSlot(StringBuilder sb, Opcode opcode, int slot) {
		if (EXPLICIT_LOCAL_OPCODES.contains(opcode)) {
			sb.append(' ').append(slot);
		}
	}

	private static String renderConstant(ConstantDesc constant) {
		if (constant instanceof String string) {
			return quote(string);
		}
		if (constant instanceof ClassDesc classDesc) {
			return classDesc.displayName() + ".class";
		}
		if (constant instanceof DirectMethodHandleDesc methodHandle) {
			return methodHandle.owner().displayName() + "::" + methodHandle.methodName()
				+ " " + MethodTypeDesc.ofDescriptor(methodHandle.lookupDescriptor()).displayDescriptor();
		}
		if (constant instanceof MethodTypeDesc methodType) {
			return methodType.displayDescriptor();
		}
		return constant.toString();
	}

	/**
	 * Renders the given string as a quoted literal, with non-printable characters
	 * escaped and very long strings elided to a prefix and suffix.
	 */
	private static String quote(String raw) {
		StringBuilder sb = new StringBuilder("\"");
		if (raw.length() <= ELIDE_THRESHOLD) {
			appendEscaped(sb, raw, 0, raw.length());
		} else {
			appendEscaped(sb, raw, 0, ELIDE_PREFIX);
			sb.append("...");
			appendEscaped(sb, raw, raw.length() - ELIDE_SUFFIX, raw.length());
		}
		return sb.append('"').toString();
	}

	private static void appendEscaped(StringBuilder sb, String string, int from, int to) {
		for (int i = from; i < to; i++) {
			char c = string.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20 || c == 0x7f) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
	}

	private static void renderAnnotations(StringBuilder sb, AttributedElement element, String indent) {
		element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(attribute ->
			attribute.annotations().forEach(annotation -> renderAnnotation(sb, annotation, indent)));
	}

	private static void renderAnnotation(StringBuilder sb, Annotation annotation, String indent) {
		sb.append(indent).append('@').append(annotation.classSymbol().displayName());
		if (!annotation.elements().isEmpty()) {
			sb.append('(');
			String separator = "";
			for (AnnotationElement element : annotation.elements()) {
				sb.append(separator).append(element.name().stringValue()).append(" = ").append(renderAnnotationValue(element.value()));
				separator = ", ";
			}
			sb.append(')');
		}
		sb.append('\n');
	}

	private static String renderAnnotationValue(AnnotationValue value) {
		if (value instanceof AnnotationValue.OfString string) {
			return quote(string.stringValue());
		}
		if (value instanceof AnnotationValue.OfInt integer) {
			return Integer.toString(integer.intValue());
		}
		if (value instanceof AnnotationValue.OfLong longValue) {
			return Long.toString(longValue.longValue());
		}
		if (value instanceof AnnotationValue.OfShort shortValue) {
			return Short.toString(shortValue.shortValue());
		}
		if (value instanceof AnnotationValue.OfByte byteValue) {
			return Byte.toString(byteValue.byteValue());
		}
		if (value instanceof AnnotationValue.OfChar charValue) {
			return "'" + charValue.charValue() + "'";
		}
		if (value instanceof AnnotationValue.OfFloat floatValue) {
			return Float.toString(floatValue.floatValue());
		}
		if (value instanceof AnnotationValue.OfDouble doubleValue) {
			return Double.toString(doubleValue.doubleValue());
		}
		if (value instanceof AnnotationValue.OfBoolean booleanValue) {
			return Boolean.toString(booleanValue.booleanValue());
		}
		if (value instanceof AnnotationValue.OfClass classValue) {
			return classValue.classSymbol().displayName() + ".class";
		}
		if (value instanceof AnnotationValue.OfEnum enumValue) {
			return enumValue.classSymbol().displayName() + "." + enumValue.constantName().stringValue();
		}
		if (value instanceof AnnotationValue.OfAnnotation annotation) {
			return renderAnnotationInline(annotation.annotation());
		}
		if (value instanceof AnnotationValue.OfArray array) {
			return array.values().stream().map(BytecodeDisassembler::renderAnnotationValue).collect(joining(", ", "{", "}"));
		}
		throw new AssertionError("Unexpected annotation value " + value);
	}

	private static String renderAnnotationInline(Annotation annotation) {
		StringBuilder sb = new StringBuilder("@").append(annotation.classSymbol().displayName());
		if (!annotation.elements().isEmpty()) {
			sb.append('(');
			String separator = "";
			for (AnnotationElement element : annotation.elements()) {
				sb.append(separator).append(element.name().stringValue()).append(" = ").append(renderAnnotationValue(element.value()));
				separator = ", ";
			}
			sb.append(')');
		}
		return sb.toString();
	}

	private static void renderBootstrapMethods(StringBuilder sb, BootstrapMethodsAttribute attribute) {
		sb.append("-- bootstrap methods\n");
		for (BootstrapMethodEntry entry : attribute.bootstrapMethods()) {
			sb.append('B').append(entry.bsmIndex()).append(": ");
			DirectMethodHandleDesc bootstrap = entry.bootstrapMethod().asSymbol();
			sb.append(bootstrap.owner().displayName()).append("::").append(bootstrap.methodName()).append('\n');
			for (var argument : entry.arguments()) {
				sb.append("    arg: ").append(renderConstant(argument.constantValue())).append('\n');
			}
		}
	}

	private static final String SWITCH_ENTRY_INDENT = "       ";
	private static final String BOOTSTRAP_INDENT = "              ";

	/**
	 * The opcodes whose mnemonic does not already encode the local variable slot,
	 * so the slot must be rendered as an operand.
	 */
	private static final Set<Opcode> EXPLICIT_LOCAL_OPCODES = Set.of(
		Opcode.ILOAD, Opcode.LLOAD, Opcode.FLOAD, Opcode.DLOAD, Opcode.ALOAD,
		Opcode.ILOAD_W, Opcode.LLOAD_W, Opcode.FLOAD_W, Opcode.DLOAD_W, Opcode.ALOAD_W,
		Opcode.ISTORE, Opcode.LSTORE, Opcode.FSTORE, Opcode.DSTORE, Opcode.ASTORE,
		Opcode.ISTORE_W, Opcode.LSTORE_W, Opcode.FSTORE_W, Opcode.DSTORE_W, Opcode.ASTORE_W
	);

	/**
	 * Strings longer than this are elided to a prefix and suffix.
	 */
	private static final int ELIDE_THRESHOLD = 50;
	private static final int ELIDE_PREFIX = 20;
	private static final int ELIDE_SUFFIX = 20;

	private BytecodeDisassembler() {}

}
