package works.bosk.codon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import works.bosk.libtesting.LogCapture;

import static java.lang.classfile.Opcode.GETFIELD;
import static java.lang.classfile.Opcode.GETSTATIC;
import static java.lang.classfile.Opcode.IF_ICMPGE;
import static java.lang.classfile.Opcode.INVOKEDYNAMIC;
import static java.lang.classfile.Opcode.INVOKEINTERFACE;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.lang.classfile.Opcode.INVOKEVIRTUAL;
import static java.lang.classfile.Opcode.LDC;
import static java.lang.classfile.Opcode.LDC2_W;
import static java.lang.classfile.Opcode.LDC_W;
import static java.lang.classfile.Opcode.MULTIANEWARRAY;
import static java.lang.classfile.Opcode.PUTFIELD;
import static java.lang.classfile.Opcode.PUTSTATIC;
import static java.lang.classfile.Opcode.values;
import static java.util.Arrays.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BytecodeDisassemblerTest {

	@Test
	void loopMethod_rendersBciLabelsBranchesAndNets() {
		assertEquals(disassembly(
			"class Example extends Object",
			"== sumUpTo(I)I ==",
			"     0: +1: iconst_0",
			"     1: -1: istore_1",
			"     2: +1: iconst_0",
			"     3: -1: istore_2",
			"L1:",
			"     4: +1: iload_2",
			"     5: +1: iload_0",
			"     6: -2: if_icmpge L2",
			"     9: +1: iload_1",
			"    10: +1: iload_2",
			"    11: -1: iadd",
			"    12: -1: istore_1",
			"    13: +0: iinc 2, 1",
			"    16: +0: goto L1",
			"L2:",
			"    19: +1: iload_1",
			"    20: -1: ireturn"), BytecodeDisassembler.disassemble(loopClass()));
	}

	@Test
	void switch_rendersPhysicalEntryBcis() {
		assertEquals(disassembly(
			"class Sw extends Object",
			"== dispatch(I)I ==",
			"     0: +1: iload_0",
			"     1: -1: tableswitch 0..1",
			"     4:       default -> L3",
			"    16:       case 0 -> L1",
			"    20:       case 1 -> L2",
			"L1:",
			"    24: +1: bipush 10",
			"    26: -1: ireturn",
			"L2:",
			"    27: +1: bipush 20",
			"    29: -1: ireturn",
			"L3:",
			"    30: +1: iconst_0",
			"    31: -1: ireturn"), BytecodeDisassembler.disassemble(switchClass()));
	}

	@Test
	void exceptionHandler_rendersAtHandlerLabel() {
		assertEquals(disassembly(
			"class Try extends Object",
			"== process(Ljava/lang/String;)Ljava/lang/String; ==",
			"L1:",
			"     0: +1: iconst_1",
			"     1: -1: pop",
			"     2: +0: goto L3",
			"L2:",
			"catch java/lang/IllegalArgumentException (L1..L2):",
			"     5: +1: ldc \"caught\"",
			"     7: -1: areturn",
			"L3:",
			"     8: +1: ldc \"ok\"",
			"    10: -1: areturn"), BytecodeDisassembler.disassemble(tryCatchClass()));
	}

	@Test
	void strings_areQuotedEscapedAndElided() {
		assertEquals(disassembly(
			"class LongStrings extends Object",
			"== pick(Ljava/lang/String;)Ljava/lang/String; ==",
			"     0: +1: ldc \"short\"",
			"     2: +1: ldc \"This is a very long ...racters, doesn't it?\"",
			"     4: +1: ldc \"tab\\there\\nand \\\"quotes\\\" and \\\\backslash\\\\\"",
			"     6: -1: areturn"), BytecodeDisassembler.disassemble(longStringClass()));
	}

	@Test
	void strings_escapeUnicodeThatWouldMuddleTheLog() {
		assertEquals(disassembly(
			"class UnicodeStrings extends Object",
			"== pick(Ljava/lang/String;)Ljava/lang/String; ==",
			"     0: +1: ldc \"a\\u0085b\\u200bc\\ud800d\"",
			"     2: -1: areturn"), BytecodeDisassembler.disassemble(unicodeStringClass()));
	}

	@Test
	void operandDependentNets() {
		String text = BytecodeDisassembler.disassemble(netClass());
		assertTrue(text.contains("     0: +2: getstatic SomeClass.dbl:D"), text);
		assertTrue(text.contains("     0: +1: getstatic SomeClass.cnt:I"), text);
		assertTrue(text.contains("     2: +0: invokestatic SomeClass.mk:(II)D"), text);
		assertTrue(text.contains("     3: -2: invokestatic SomeClass.use:(J)V"), text);
		assertTrue(text.contains("     0: +2: ldc2_w 3"), text);
		assertTrue(text.contains("     2: -1: multianewarray [[I dims=2"), text);
	}

	@Test
	void fixedNets_forLongAndDoubleOperands() {
		String text = BytecodeDisassembler.disassemble(longDoubleClass());
		assertTrue(text.contains("     2: +0: laload"), text);
		assertTrue(text.contains("     3: -4: lastore"), text);
		assertTrue(text.contains("     2: +0: daload"), text);
		assertTrue(text.contains("     3: -4: dastore"), text);
		assertTrue(text.contains("     2: -2: ladd"), text);
		assertTrue(text.contains("     2: -2: dadd"), text);
		assertTrue(text.contains("     2: -3: lcmp"), text);
		assertTrue(text.contains("     2: -3: dcmpl"), text);
		assertTrue(text.contains("     1: +1: i2l"), text);
		assertTrue(text.contains("     1: -1: l2i"), text);
		assertTrue(text.contains("     1: +0: d2l"), text);
	}

	@Test
	void everyOpcode_hasANetEffect() {
		Set<Opcode> operandDependent = Set.of(
			LDC, LDC_W, LDC2_W,
			GETSTATIC, PUTSTATIC, GETFIELD, PUTFIELD,
			INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE,
			INVOKEDYNAMIC, MULTIANEWARRAY
		);
		for (Opcode opcode : values()) {
			assertTrue(StackEffects.FIXED_NET.containsKey(opcode) || operandDependent.contains(opcode),
				"No net effect defined for " + opcode);
		}
	}

	@Test
	void javacClass_rendersAnnotationsInvokedynamicAndBootstrapMethods() {
		String text = BytecodeDisassembler.disassemble(javacClass("""
			@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
			@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD})
			@interface Marker { String value(); }
			@Marker("class")
			public class Sample {
				@Marker("method")
				public String process(String s) {
					return "hello " + s;
				}
				public Runnable makeRunnable() { return () -> System.out.println("run"); }
			}
			"""));
		assertTrue(text.contains("@Marker(value = \"class\")"), text);
		assertTrue(text.contains("class Sample extends Object"), text);
		assertTrue(text.contains("== process(Ljava/lang/String;)Ljava/lang/String; =="), text);
		assertTrue(text.contains("invokedynamic makeConcatWithConstants:(Ljava/lang/String;)Ljava/lang/String;"), text);
		assertTrue(text.contains("bootstrap B0: StringConcatFactory::makeConcatWithConstants"), text);
		assertTrue(text.contains("invokedynamic run:()Ljava/lang/Runnable;"), text);
		assertTrue(text.contains("bootstrap B1: LambdaMetafactory::metafactory"), text);
		assertTrue(text.contains("-- bootstrap methods"), text);
		assertTrue(text.contains("arg: \"hello \\u0001\""), text);
		assertTrue(text.contains("arg: Sample::lambda$makeRunnable$0 ()void"), text);
	}

	@Test
	void log_logsDisassemblyWhenTraceEnabled() {
		try (LogCapture capture = LogCapture.capture(BytecodeDisassembler.class)) {
			Logger logger = LogCapture.logger(BytecodeDisassembler.class);
			logger.setLevel(Level.TRACE);
			BytecodeDisassembler.log(logger, org.slf4j.event.Level.TRACE, loopClass());
			assertEquals(1, capture.formattedMessages().size(), "Exactly one trace event expected");
			assertTrue(capture.formattedMessages().getFirst().contains("== sumUpTo(I)I =="),
				"Disassembly should include the sumUpTo method");
		}
	}

	private static byte[] loopClass() {
		return ClassFile.of().build(ClassDesc.of("Example"), cb -> {
			cb.withMethodBody("sumUpTo", mtd(int.class, int.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					Label loop = cob.newLabel();
					Label end = cob.newLabel();
					cob.loadConstant(0);
					cob.istore(cob.allocateLocal(TypeKind.INT));
					cob.loadConstant(0);
					cob.istore(cob.allocateLocal(TypeKind.INT));
					cob.labelBinding(loop);
					cob.iload(2);
					cob.iload(0);
					cob.branch(IF_ICMPGE, end);
					cob.iload(1);
					cob.iload(2);
					cob.iadd();
					cob.istore(1);
					cob.iinc(2, 1);
					cob.goto_(loop);
					cob.labelBinding(end);
					cob.iload(1);
					cob.ireturn();
				});
		});
	}

	private static byte[] switchClass() {
		return ClassFile.of().build(ClassDesc.of("Sw"), cb -> {
			cb.withMethodBody("dispatch", mtd(int.class, int.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					Label def = cob.newLabel();
					Label c0 = cob.newLabel();
					Label c1 = cob.newLabel();
					cob.iload(0);
					cob.tableswitch(0, 1, def,
						List.of(SwitchCase.of(0, c0), SwitchCase.of(1, c1)));
					cob.labelBinding(c0);
					cob.loadConstant(10);
					cob.ireturn();
					cob.labelBinding(c1);
					cob.loadConstant(20);
					cob.ireturn();
					cob.labelBinding(def);
					cob.loadConstant(0);
					cob.ireturn();
				});
		});
	}

	private static byte[] tryCatchClass() {
		return ClassFile.of().build(ClassDesc.of("Try"), cb -> {
			cb.withMethodBody("process", mtd(String.class, String.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					Label tryStart = cob.newLabel();
					Label tryEnd = cob.newLabel();
					Label handler = cob.newLabel();
					Label after = cob.newLabel();
					ClassDesc exception = ClassDesc.of("java.lang.IllegalArgumentException");
					cob.labelBinding(tryStart);
					cob.loadConstant(1);
					cob.pop();
					cob.goto_(after);
					cob.labelBinding(tryEnd);
					cob.labelBinding(handler);
					cob.ldc("caught");
					cob.areturn();
					cob.labelBinding(after);
					cob.ldc("ok");
					cob.areturn();
					cob.exceptionCatch(tryStart, tryEnd, handler, exception);
				});
		});
	}

	private static byte[] longStringClass() {
		return ClassFile.of().build(ClassDesc.of("LongStrings"), cb -> {
			cb.withMethodBody("pick", mtd(String.class, String.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.ldc("short");
					cob.ldc("This is a very long string that definitely exceeds fifty characters, doesn't it?");
					cob.ldc("tab\there\nand \"quotes\" and \\backslash\\");
					cob.areturn();
				});
		});
	}

	private static byte[] unicodeStringClass() {
		return ClassFile.of().build(ClassDesc.of("UnicodeStrings"), cb -> {
			cb.withMethodBody("pick", mtd(String.class, String.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.ldc("a\u0085b\u200bc\ud800d");
					cob.areturn();
				});
		});
	}

	private static byte[] netClass() {
		return ClassFile.of().build(ClassDesc.of("Nets"), cb -> {
			ClassDesc some = ClassDesc.of("SomeClass");
			cb.withMethodBody("dblField", mtd(double.class), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
				cob.getstatic(some, "dbl", ClassDesc.ofDescriptor("D"));
				cob.dreturn();
			});
			cb.withMethodBody("cntField", mtd(int.class), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
				cob.getstatic(some, "cnt", ClassDesc.ofDescriptor("I"));
				cob.ireturn();
			});
			cb.withMethodBody("mkCall", mtd(double.class), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
				cob.loadConstant(1);
				cob.loadConstant(2);
				cob.invokestatic(some, "mk", MethodTypeDesc.ofDescriptor("(II)D"));
				cob.dreturn();
			});
			cb.withMethodBody("useCall", mtd(void.class), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
				cob.loadConstant(3L);
				cob.invokestatic(some, "use", MethodTypeDesc.ofDescriptor("(J)V"));
				cob.return_();
			});
			cb.withMethodBody("ldc2", mtd(long.class), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
				cob.loadConstant(3L);
				cob.lreturn();
			});
			cb.withMethodBody("arr", mtd(int.class), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
				cob.loadConstant(2);
				cob.loadConstant(1);
				cob.multianewarray(cob.constantPool().classEntry(ClassDesc.ofDescriptor("[[I")), 2);
				cob.arraylength();
				cob.ireturn();
			});
		});
	}

	private static byte[] longDoubleClass() {
		return ClassFile.of().build(ClassDesc.of("LongDouble"), cb -> {
			cb.withMethodBody("loadLong", mtd(long.class, long[].class, int.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.aload(0);
					cob.iload(1);
					cob.laload();
					cob.lreturn();
				});
			cb.withMethodBody("storeLong", mtd(void.class, long[].class, int.class, long.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.aload(0);
					cob.iload(1);
					cob.lload(2);
					cob.lastore();
					cob.return_();
				});
			cb.withMethodBody("loadDouble", mtd(double.class, double[].class, int.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.aload(0);
					cob.iload(1);
					cob.daload();
					cob.dreturn();
				});
			cb.withMethodBody("storeDouble", mtd(void.class, double[].class, int.class, double.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.aload(0);
					cob.iload(1);
					cob.dload(2);
					cob.dastore();
					cob.return_();
				});
			cb.withMethodBody("addLongs", mtd(long.class, long.class, long.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.lload(0);
					cob.lload(2);
					cob.ladd();
					cob.lreturn();
				});
			cb.withMethodBody("addDoubles", mtd(double.class, double.class, double.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.dload(0);
					cob.dload(2);
					cob.dadd();
					cob.dreturn();
				});
			cb.withMethodBody("compareLongs", mtd(int.class, long.class, long.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.lload(0);
					cob.lload(2);
					cob.lcmp();
					cob.ireturn();
				});
			cb.withMethodBody("compareDoubles", mtd(int.class, double.class, double.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.dload(0);
					cob.dload(2);
					cob.dcmpl();
					cob.ireturn();
				});
			cb.withMethodBody("widen", mtd(long.class, int.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.iload(0);
					cob.i2l();
					cob.lreturn();
				});
			cb.withMethodBody("narrow", mtd(int.class, long.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.lload(0);
					cob.l2i();
					cob.ireturn();
				});
			cb.withMethodBody("doubleToLong", mtd(long.class, double.class),
				ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
					cob.dload(0);
					cob.d2l();
					cob.lreturn();
				});
		});
	}

	/** The class file bytes produced by compiling the given source in memory. */
	private static byte[] javacClass(String source) {
		Map<String, byte[]> outputs = new HashMap<>();
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
		JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
				JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
				return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								outputs.put(className, toByteArray());
							}
						};
					}
				};
			}
		};
		SimpleJavaFileObject sourceObject = new SimpleJavaFileObject(URI.create("string:///Sample.java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};
		boolean compiled = compiler.getTask(null, manager, null, List.of("-g"), null, List.of(sourceObject)).call();
		if (!compiled) {
			throw new AssertionError("Failed to compile test source");
		}
		return outputs.get("Sample");
	}

	private static MethodTypeDesc mtd(Class<?> returnType, Class<?>... parameterTypes) {
		return MethodTypeDesc.of(
			returnType.describeConstable().orElseThrow(),
			stream(parameterTypes).map(c -> c.describeConstable().orElseThrow()).toArray(ClassDesc[]::new));
	}

	/** Joins the given lines into the expected disassembly text. */
	private static String disassembly(String... lines) {
		return String.join("\n", lines) + "\n";
	}

}
