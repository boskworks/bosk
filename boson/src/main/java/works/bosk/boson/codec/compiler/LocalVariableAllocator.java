package works.bosk.boson.codec.compiler;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.util.BitSet;

import static java.lang.classfile.TypeKind.VOID;

/**
 * Make one of these per method.
 * Within the method, use {@link #newScope()} to acquire a {@link LocalVariableScope}
 * from which you can {@link LocalVariableScope#allocate allocate} locals.
 */
public class LocalVariableAllocator {
	final BitSet busy;

	/**
	 * @param numParameterSlots includes the receiver (`this`) for virtual methods
	 */
	public LocalVariableAllocator(int numParameterSlots) {
		busy = new BitSet();
		busy.set(0, numParameterSlots);
	}

	public LocalVariableScope newScope() {
		return new LocalVariableScope();
	}

	public record LocalVariable(TypeKind typeKind, int firstSlot) {
		public void load(CodeBuilder cb) {
			cb.loadLocal(typeKind, firstSlot);
		}

		public void store(CodeBuilder cb) {
			cb.storeLocal(typeKind, firstSlot);
		}
	}

	/**
	 * An {@link AutoCloseable} that releases all slots reserved by {@link #allocate}
	 * when {@link #close()} is called.
	 */
	public final class LocalVariableScope implements AutoCloseable {
		final BitSet owned;

		public LocalVariableScope() {
			owned = new BitSet();
		}

		public LocalVariable allocate(TypeKind typeKind) {
			assert typeKind != VOID;
			int startSlot = findSlots(typeKind);
			busy.set(startSlot, startSlot + typeKind.slotSize());
			owned.set(startSlot, startSlot + typeKind.slotSize());
			return new LocalVariable(typeKind, startSlot);
		}

		private int findSlots(TypeKind typeKind) {
			eachCandidate:
			for (int candidate = busy.nextClearBit(0); ; candidate = busy.nextClearBit(candidate)) {
				for (int i = 0; i < typeKind.slotSize(); i++) {
					if (busy.get(candidate + i)) {
						candidate += i; // Continue the search after this bit we know to be set
						continue eachCandidate;
					}
				}
				return candidate;
			}
		}

		@Override
		public void close() {
			busy.andNot(owned);
		}
	}
}
