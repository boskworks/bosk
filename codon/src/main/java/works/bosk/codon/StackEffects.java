package works.bosk.codon;

import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.EnumMap;

/**
 * Computes the net effect of an instruction on the depth of the operand stack,
 * in stack slots. A {@code long} or {@code double} occupies two slots.
 */
final class StackEffects {

	/**
	 * The net change in operand stack depth (pushes minus pops) for instructions
	 * whose effect does not depend on their operands.
	 */
	static final EnumMap<Opcode, Integer> FIXED_NET = fixedNets();

	private static EnumMap<Opcode, Integer> fixedNets() {
		EnumMap<Opcode, Integer> net = new EnumMap<>(Opcode.class);
		// Constants that push one slot
		for (Opcode opcode : new Opcode[] {
			Opcode.ACONST_NULL, Opcode.ICONST_M1, Opcode.ICONST_0, Opcode.ICONST_1, Opcode.ICONST_2,
			Opcode.ICONST_3, Opcode.ICONST_4, Opcode.ICONST_5, Opcode.BIPUSH, Opcode.SIPUSH
		}) {
			net.put(opcode, +1);
		}
		// Constants that push two slots
		net.put(Opcode.LCONST_0, +2);
		net.put(Opcode.LCONST_1, +2);
		net.put(Opcode.DCONST_0, +2);
		net.put(Opcode.DCONST_1, +2);
		// Float constants push one slot
		net.put(Opcode.FCONST_0, +1);
		net.put(Opcode.FCONST_1, +1);
		net.put(Opcode.FCONST_2, +1);
		// Local variable loads
		for (Opcode opcode : new Opcode[] {
			Opcode.ILOAD, Opcode.ILOAD_0, Opcode.ILOAD_1, Opcode.ILOAD_2, Opcode.ILOAD_3,
			Opcode.FLOAD, Opcode.FLOAD_0, Opcode.FLOAD_1, Opcode.FLOAD_2, Opcode.FLOAD_3,
			Opcode.ALOAD, Opcode.ALOAD_0, Opcode.ALOAD_1, Opcode.ALOAD_2, Opcode.ALOAD_3,
			Opcode.ILOAD_W, Opcode.FLOAD_W, Opcode.ALOAD_W
		}) {
			net.put(opcode, +1);
		}
		for (Opcode opcode : new Opcode[] {
			Opcode.LLOAD, Opcode.LLOAD_0, Opcode.LLOAD_1, Opcode.LLOAD_2, Opcode.LLOAD_3,
			Opcode.DLOAD, Opcode.DLOAD_0, Opcode.DLOAD_1, Opcode.DLOAD_2, Opcode.DLOAD_3,
			Opcode.LLOAD_W, Opcode.DLOAD_W
		}) {
			net.put(opcode, +2);
		}
		// Local variable stores
		for (Opcode opcode : new Opcode[] {
			Opcode.ISTORE, Opcode.ISTORE_0, Opcode.ISTORE_1, Opcode.ISTORE_2, Opcode.ISTORE_3,
			Opcode.FSTORE, Opcode.FSTORE_0, Opcode.FSTORE_1, Opcode.FSTORE_2, Opcode.FSTORE_3,
			Opcode.ASTORE, Opcode.ASTORE_0, Opcode.ASTORE_1, Opcode.ASTORE_2, Opcode.ASTORE_3,
			Opcode.ISTORE_W, Opcode.FSTORE_W, Opcode.ASTORE_W
		}) {
			net.put(opcode, -1);
		}
		for (Opcode opcode : new Opcode[] {
			Opcode.LSTORE, Opcode.LSTORE_0, Opcode.LSTORE_1, Opcode.LSTORE_2, Opcode.LSTORE_3,
			Opcode.DSTORE, Opcode.DSTORE_0, Opcode.DSTORE_1, Opcode.DSTORE_2, Opcode.DSTORE_3,
			Opcode.LSTORE_W, Opcode.DSTORE_W
		}) {
			net.put(opcode, -2);
		}
		// Array loads (arrayref, index) -> value
		for (Opcode opcode : new Opcode[] {
			Opcode.IALOAD, Opcode.LALOAD, Opcode.FALOAD, Opcode.DALOAD, Opcode.AALOAD,
			Opcode.BALOAD, Opcode.CALOAD, Opcode.SALOAD
		}) {
			net.put(opcode, -1);
		}
		// Array stores (arrayref, index, value) -> nothing
		for (Opcode opcode : new Opcode[] {
			Opcode.IASTORE, Opcode.LASTORE, Opcode.FASTORE, Opcode.DASTORE, Opcode.AASTORE,
			Opcode.BASTORE, Opcode.CASTORE, Opcode.SASTORE
		}) {
			net.put(opcode, -3);
		}
		// Stack manipulation
		net.put(Opcode.POP, -1);
		net.put(Opcode.POP2, -2);
		net.put(Opcode.DUP, +1);
		net.put(Opcode.DUP_X1, +1);
		net.put(Opcode.DUP_X2, +1);
		net.put(Opcode.DUP2, +2);
		net.put(Opcode.DUP2_X1, +2);
		net.put(Opcode.DUP2_X2, +2);
		net.put(Opcode.SWAP, 0);
		// Binary arithmetic (two operands -> one result)
		for (Opcode opcode : new Opcode[] {
			Opcode.IADD, Opcode.LADD, Opcode.FADD, Opcode.DADD,
			Opcode.ISUB, Opcode.LSUB, Opcode.FSUB, Opcode.DSUB,
			Opcode.IMUL, Opcode.LMUL, Opcode.FMUL, Opcode.DMUL,
			Opcode.IDIV, Opcode.LDIV, Opcode.FDIV, Opcode.DDIV,
			Opcode.IREM, Opcode.LREM, Opcode.FREM, Opcode.DREM,
			Opcode.ISHL, Opcode.LSHL, Opcode.ISHR, Opcode.LSHR, Opcode.IUSHR, Opcode.LUSHR,
			Opcode.IAND, Opcode.LAND, Opcode.IOR, Opcode.LOR, Opcode.IXOR, Opcode.LXOR
		}) {
			net.put(opcode, -1);
		}
		// Unary arithmetic
		for (Opcode opcode : new Opcode[] {Opcode.INEG, Opcode.LNEG, Opcode.FNEG, Opcode.DNEG}) {
			net.put(opcode, 0);
		}
		// Conversions
		for (Opcode opcode : new Opcode[] {
			Opcode.I2L, Opcode.I2F, Opcode.I2D, Opcode.L2I, Opcode.L2F, Opcode.L2D,
			Opcode.F2I, Opcode.F2L, Opcode.F2D, Opcode.D2I, Opcode.D2L, Opcode.D2F,
			Opcode.I2B, Opcode.I2C, Opcode.I2S
		}) {
			net.put(opcode, 0);
		}
		net.put(Opcode.IINC, 0);
		net.put(Opcode.IINC_W, 0);
		// Comparisons (two operands -> one result)
		for (Opcode opcode : new Opcode[] {Opcode.LCMP, Opcode.FCMPL, Opcode.FCMPG, Opcode.DCMPL, Opcode.DCMPG}) {
			net.put(opcode, -1);
		}
		// Conditional branches
		for (Opcode opcode : new Opcode[] {
			Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT, Opcode.IFGE, Opcode.IFGT, Opcode.IFLE,
			Opcode.IFNULL, Opcode.IFNONNULL
		}) {
			net.put(opcode, -1);
		}
		for (Opcode opcode : new Opcode[] {
			Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT, Opcode.IF_ICMPGE,
			Opcode.IF_ICMPGT, Opcode.IF_ICMPLE, Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE
		}) {
			net.put(opcode, -2);
		}
		net.put(Opcode.GOTO, 0);
		net.put(Opcode.GOTO_W, 0);
		net.put(Opcode.JSR, +1);
		net.put(Opcode.JSR_W, +1);
		net.put(Opcode.RET, 0);
		net.put(Opcode.RET_W, 0);
		net.put(Opcode.TABLESWITCH, -1);
		net.put(Opcode.LOOKUPSWITCH, -1);
		// Returns
		for (Opcode opcode : new Opcode[] {Opcode.IRETURN, Opcode.FRETURN, Opcode.ARETURN}) {
			net.put(opcode, -1);
		}
		for (Opcode opcode : new Opcode[] {Opcode.LRETURN, Opcode.DRETURN}) {
			net.put(opcode, -2);
		}
		net.put(Opcode.RETURN, 0);
		// Object operations with a fixed effect
		net.put(Opcode.NEW, +1);
		net.put(Opcode.NEWARRAY, 0);
		net.put(Opcode.ANEWARRAY, 0);
		net.put(Opcode.ARRAYLENGTH, 0);
		net.put(Opcode.CHECKCAST, 0);
		net.put(Opcode.INSTANCEOF, 0);
		net.put(Opcode.ATHROW, -1);
		net.put(Opcode.MONITORENTER, -1);
		net.put(Opcode.MONITOREXIT, -1);
		net.put(Opcode.NOP, 0);
		return net;
	}

	/**
	 * The net change in operand stack depth caused by the given instruction.
	 */
	static int net(Instruction instruction) {
		Integer fixed = FIXED_NET.get(instruction.opcode());
		if (fixed != null) {
			return fixed;
		}
		return switch (instruction.opcode()) {
			case LDC, LDC_W -> ((ConstantInstruction.LoadConstantInstruction) instruction).typeKind().slotSize();
			case LDC2_W -> 2;
			case GETSTATIC -> fieldSlots((FieldInstruction) instruction);
			case PUTSTATIC -> -fieldSlots((FieldInstruction) instruction);
			case GETFIELD -> fieldSlots((FieldInstruction) instruction) - 1;
			case PUTFIELD -> -(1 + fieldSlots((FieldInstruction) instruction));
			case INVOKEVIRTUAL, INVOKESPECIAL, INVOKEINTERFACE -> invokeNet((InvokeInstruction) instruction, true);
			case INVOKESTATIC -> invokeNet((InvokeInstruction) instruction, false);
			case INVOKEDYNAMIC -> invokeNet((InvokeDynamicInstruction) instruction);
			case MULTIANEWARRAY -> 1 - ((NewMultiArrayInstruction) instruction).dimensions();
			default -> throw new AssertionError("Unhandled opcode " + instruction.opcode());
		};
	}

	private static int fieldSlots(FieldInstruction instruction) {
		return slots(instruction.typeSymbol());
	}

	private static int invokeNet(InvokeInstruction instruction, boolean hasReceiver) {
		MethodTypeDesc type = instruction.typeSymbol();
		int receiverSlots = hasReceiver ? 1 : 0;
		return returnSlots(type) - (parameterSlots(type) + receiverSlots);
	}

	private static int invokeNet(InvokeDynamicInstruction instruction) {
		MethodTypeDesc type = instruction.typeSymbol();
		return returnSlots(type) - parameterSlots(type);
	}

	private static int parameterSlots(MethodTypeDesc type) {
		int slots = 0;
		for (int i = 0; i < type.parameterCount(); i++) {
			slots += slots(type.parameterType(i));
		}
		return slots;
	}

	private static int returnSlots(MethodTypeDesc type) {
		return slots(type.returnType());
	}

	/**
	 * The number of operand stack slots occupied by a value of the given type.
	 */
	private static int slots(ClassDesc type) {
		if (type.equals(ConstantDescs.CD_void)) {
			return 0;
		}
		if (type.equals(ConstantDescs.CD_long) || type.equals(ConstantDescs.CD_double)) {
			return 2;
		}
		return 1;
	}

	private StackEffects() {}

}
