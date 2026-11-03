package works.bosk;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.IntConsumer;

import static java.lang.Integer.bitCount;
import static java.lang.Integer.numberOfTrailingZeros;
import static java.lang.System.arraycopy;
import static java.util.Arrays.binarySearch;
import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;
import static java.util.Spliterator.SIZED;
import static java.util.Spliterator.SUBSIZED;

/**
 * A set of non-negative {@code int} values, represented as a hybrid of the
 * sparse and dense representations.
 *
 * <p>
 * One {@code int[]} holds both: a bitvector over the low values, then a sorted
 * tail of the values above the bitvector's range. The tail starts at
 * {@code tailStart}, so the bitvector covers {@code 0..tailStart * 32 - 1},
 * and the boundary is chosen dynamically to make efficient use of memory.
 * Sparse sets keep everything in the tail; dense sets keep everything in the
 * bitvector; a dense low range plus a few far values uses both. The boundary
 * moves as values are added and removed.
 *
 * <p>
 * Values are non-negative. {@link #add(int)} rejects negative values, while
 * {@link #contains(int)} and {@link #remove(int)} report false for them.
 * Iteration is in ascending order, and the iterator supports removal.
 * Instances are not thread-safe.
 *
 * <p>
 * A dense set costs one bit per index; a sparse set costs an int per value.
 * Sparse additions and removals shift the sorted tail, so they are linear in
 * the number of sparse values. This is intended for small per-reference sets,
 * not for large sparse sets.
 *
 * <p>
 * Values are indices from zero. If a dense range starts at a large index,
 * subtract the starting index before inserting; otherwise the bitvector has to
 * cover the whole range from zero.
 */
final class IntSet extends AbstractSet<Integer> {
	private int[] data = EMPTY;
	private int tailStart;
	private int tailCount;
	private int bitVectorPopulation;

	@Override
	public int size() {
		return bitVectorPopulation + tailCount;
	}

	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public boolean contains(Object o) {
		return o instanceof Integer value && contains((int) value);
	}

	@Override
	public boolean add(Integer value) {
		return add((int) value);
	}

	@Override
	public boolean remove(Object o) {
		return o instanceof Integer value && remove((int) value);
	}

	public boolean contains(int value) {
		if (value < 0) {
			return false;
		}
		int wordIndex = value >>> 5;
		if (wordIndex < tailStart) {
			return (data[wordIndex] & (1 << (value & 31))) != 0;
		} else {
			return tailIndexOf(value) >= 0;
		}
	}

	public boolean add(int value) {
		if (value < 0) {
			throw new IllegalArgumentException("IntSet values must be non-negative: " + value);
		}
		if ((value >>> 5) < tailStart) {
			return addBit(value);
		} else {
			return addTail(value);
		}
	}

	private boolean addBit(int value) {
		int wordIndex = value >>> 5;
		int mask = 1 << (value & 31);
		var oldWord = data[wordIndex];
		var newWord = oldWord | mask;
		if (newWord == oldWord) {
			return false;
		} else {
			data[wordIndex] = newWord;
			bitVectorPopulation++;
			return true;
		}
	}

	private boolean addTail(int value) {
		int index = tailIndexOf(value);
		if (index >= 0) {
			// The value is already in the tail.
			return false;
		}
		int insertionPoint = -index - 1;

		// Ensure capacity, then shift the tail above the insertion point to open a gap.
		ensureCapacity(tailStart + tailCount + 1);
		arraycopy(data, insertionPoint, data, insertionPoint + 1, tailStart + tailCount - insertionPoint);

		data[insertionPoint] = value;
		tailCount++;
		rebalance();
		return true;
	}

	public boolean remove(int value) {
		boolean removed = removeCore(value);
		if (removed) {
			sparsifyIfWorthwhile();
		}
		return removed;
	}

	/**
	 * Removes a value without sparsifying, so that iterator removals don't pay
	 * for an array rebuild; {@link #remove(int)} adds that step.
	 */
	private boolean removeCore(int value) {
		if (value < 0) {
			return false;
		}
		if ((value >>> 5) < tailStart) {
			return removeBit(value);
		} else {
			return removeTail(value);
		}
	}

	private boolean removeBit(int value) {
		int wordIndex = value >>> 5;
		int mask = 1 << (value & 31);
		var oldWord = data[wordIndex];
		var newWord = oldWord & ~mask;
		if (newWord == oldWord) {
			return false;
		} else {
			data[wordIndex] = newWord;
			bitVectorPopulation--;
			shrinkEmptyTopWords();
			return true;
		}
	}

	private boolean removeTail(int value) {
		int index = tailIndexOf(value);
		if (index < 0) {
			return false;
		} else {
			int end = tailStart + tailCount;
			arraycopy(data, index + 1, data, index, end - index - 1);
			tailCount--;
			return true;
		}
	}

	@Override
	public void clear() {
		data = EMPTY;
		tailStart = 0;
		tailCount = 0;
		bitVectorPopulation = 0;
	}

	@Override
	public Iterator<Integer> iterator() {
		return new IntIterator();
	}

	@Override
	public Spliterator<Integer> spliterator() {
		return Spliterators.spliterator(this,
			DISTINCT | ORDERED | SIZED | SUBSIZED | NONNULL);
	}

	/**
	 * Visits every value in ascending order without boxing.
	 */
	public void forEachInt(IntConsumer action) {
		forEachBit(action);
		forEachTail(action);
	}

	private void forEachBit(IntConsumer action) {
		int tailStart = this.tailStart;
		int[] data = this.data;
		for (int w = 0; w < tailStart; w++) {
			int bits = data[w];
			while (bits != 0) {
				action.accept((w << 5) + numberOfTrailingZeros(bits));
				bits &= bits - 1;
			}
		}
	}

	private void forEachTail(IntConsumer action) {
		int tailStart = this.tailStart;
		int tailCount = this.tailCount;
		int[] data = this.data;
		for (int i = tailStart; i < tailStart + tailCount; i++) {
			action.accept(data[i]);
		}
	}

	int tailStart() {
		return tailStart;
	}

	int tailCount() {
		return tailCount;
	}

	/**
	 * Checks the representation invariants. Used by tests; assertions must be enabled.
	 */
	void assertInvariants() {
		int tailStart = this.tailStart;
		int tailCount = this.tailCount;
		int[] data = this.data;
		assert 0 <= tailStart : "tailStart must be non-negative";
		assert 0 <= tailCount : "tailCount must be non-negative";
		assert tailStart + tailCount <= data.length : "the used region must fit within the array";
		int bitsInVector = 0;
		for (int w = 0; w < tailStart; w++) {
			bitsInVector += bitCount(data[w]);
		}
		assert bitsInVector == bitVectorPopulation : "bitVectorPopulation must equal the number of set bits in the bitvector (" + bitsInVector + ")";
		if (tailStart > 0) {
			assert data[tailStart - 1] != 0 : "the last bitvector word must be non-empty";
		}
		for (int i = tailStart; i < tailStart + tailCount; i++) {
			int value = data[i];
			assert value >= 0 : "tail values must be non-negative";
			assert (value >>> 5) >= tailStart : "tail value " + value + " must be at or above the bitvector boundary";
			if (i > tailStart) {
				assert data[i - 1] < value : "tail values must be in strictly ascending order";
			}
		}
	}

	private int tailIndexOf(int value) {
		return binarySearch(data, tailStart, tailStart + tailCount, value);
	}

	private void ensureCapacity(int required) {
		if (required > data.length) {
			int newCapacity;
			if (data.length == 0) {
				// Five ints is 32 bytes with compact object headers, with no
				// padding, and covers the common small sets without a resize.
				newCapacity = 5;
			} else if (data.length >= Integer.MAX_VALUE / 2) {
				newCapacity = required;
			} else {
				newCapacity = data.length * 2;
			}
			if (newCapacity < required) {
				newCapacity = required;
			}
			data = Arrays.copyOf(data, newCapacity);
		}
	}

	private void rebalance() {
		shrinkEmptyTopWords();
		growToBestPrefix();
	}

	/**
	 * Removes trailing empty words from the bitvector, moving the tail down to
	 * meet the new boundary. A trailing word with one value would break even if
	 * that value moved to the tail; with two or more values it would use more
	 * space. A mostly-empty top word can still be worthwhile as part of a
	 * suffix that also removes empty words below it, but finding that suffix
	 * means scanning the bitvector on every removal. {@link #sparsifyIfWorthwhile}
	 * handles the case where keeping the bitvector is no longer worthwhile.
	 */
	private void shrinkEmptyTopWords() {
		int newTailStart = tailStart;
		while (newTailStart > 0 && data[newTailStart - 1] == 0) {
			newTailStart--;
		}
		if (newTailStart < tailStart) {
			if (tailCount > 0) {
				arraycopy(data, tailStart, data, newTailStart, tailCount);
			}
			tailStart = newTailStart;
		}
	}

	/**
	 * Sparsifies when removals have left the bitvector mostly empty. Keeping
	 * the bitvector only while it has at least one value per two words bounds
	 * it at twice the size of the sparse representation, and the factor of two
	 * avoids oscillating at the break-even point.
	 */
	private void sparsifyIfWorthwhile() {
		if (tailStart > 0) {
			if ((long) bitVectorPopulation * 2 < tailStart) {
				sparsify();
			}
		}
	}

	/**
	 * Moves every value into the tail, leaving no bitvector.
	 */
	private void sparsify() {
		int total = size();
		if (total == 0) {
			clear();
			return;
		}

		// Build a fresh array: the bitvector values in ascending order, then
		// the existing tail values.
		int[] values = new int[total];
		int index = 0;
		for (int w = 0; w < tailStart; w++) {
			int bits = data[w];
			while (bits != 0) {
				values[index++] = (w << 5) + numberOfTrailingZeros(bits);
				bits &= bits - 1;
			}
		}
		for (int i = tailStart; i < tailStart + tailCount; i++) {
			values[index++] = data[i];
		}
		assert index == total : "the rebuilt values array must contain every value";

		// Install the new array as the sparse representation.
		data = values;
		tailStart = 0;
		tailCount = total;
		bitVectorPopulation = 0;
	}

	/**
	 * Grows the bitvector from {@code tailStart} over the prefix of the tail
	 * that saves the most space. The caller must have removed empty words from
	 * the top of the bitvector, and the tail must be sorted and start above the
	 * bitvector.
	 */
	private void growToBestPrefix() {
		assert tailStart == 0 || data[tailStart - 1] != 0 : "the bitvector must end in a non-empty word";
		assert tailCount == 0 || (data[tailStart] >>> 5) >= tailStart : "tail values must be at or above the boundary";

		int newTailStart = bestTailStart();
		if (newTailStart > tailStart) {
			growBitvector(newTailStart);
		}
	}

	/**
	 * Returns the end of the prefix of the tail that saves the most space, or
	 * {@code tailStart} if no prefix is worthwhile. Each extra word costs one
	 * int and absorbs the tail values in it, so a prefix is worth taking when
	 * it absorbs more values than it adds words.
	 */
	private int bestTailStart() {
		int base = tailStart;
		int end = base + tailCount;
		int[] data = this.data;
		int i = base;
		int values = 0;
		int bestTailStart = base;
		int bestDelta = 0;
		while (i < end) {
			int wordIndex = data[i] >>> 5;
			int group = 0;
			while (i < end && (data[i] >>> 5) == wordIndex) {
				group++;
				i++;
			}
			values += group;
			int delta = values - (wordIndex + 1 - base);
			if (delta > bestDelta) {
				bestDelta = delta;
				bestTailStart = wordIndex + 1;
			}
		}
		return bestTailStart;
	}

	/**
	 * Grows the bitvector by absorbing the tail values below {@code newTailStart}.
	 * This always shrinks the used portion, because it is only called with a
	 * prefix that absorbs more values than it adds words.
	 */
	private void growBitvector(int newTailStart) {
		assert newTailStart > tailStart : "the bitvector must grow";
		int base = tailStart;
		int[] data = this.data;
		int absorbed = 0;
		while (base + absorbed < base + tailCount && (data[base + absorbed] >>> 5) < newTailStart) {
			absorbed++;
		}
		int remaining = tailCount - absorbed;
		int wordCount = newTailStart - base;
		assert wordCount < absorbed : "the absorbed prefix must save space";

		// Build the words at the end of the absorbed values, reading those
		// values from the end, so each word only overwrites values that have
		// already been folded in. The words above the current one are empty;
		// fill them now that their slots are below the unread values.
		int wordBlock = base + absorbed - wordCount;
		int i = base + absorbed - 1;
		int nextWord = newTailStart;
		while (i >= base) {
			int wordIndex = data[i] >>> 5;
			int bits = 0;
			while (i >= base && (data[i] >>> 5) == wordIndex) {
				bits |= 1 << (data[i] & 31);
				i--;
			}
			for (int w = wordIndex + 1; w < nextWord; w++) {
				data[wordBlock + (w - base)] = 0;
			}
			data[wordBlock + (wordIndex - base)] = bits;
			nextWord = wordIndex;
		}
		for (int w = base; w < nextWord; w++) {
			data[wordBlock + (w - base)] = 0;
		}

		// Move the words down to the front, then close the gap left by the
		// absorbed values.
		arraycopy(data, wordBlock, data, base, wordCount);
		arraycopy(data, base + absorbed, data, newTailStart, remaining);
		tailStart = newTailStart;
		bitVectorPopulation += absorbed;
		tailCount = remaining;
		assert tailStart == 0 || data[tailStart - 1] != 0 : "the last bitvector word must be non-empty after growth";
	}

	private final class IntIterator implements PrimitiveIterator.OfInt {
		private int wordIndex;
		private int bits;
		private int tailIndex;
		private int lastReturnedIndex = -1;
		private boolean canRemove;

		IntIterator() {
			bits = tailStart > 0 ? data[0] : 0;
			tailIndex = tailStart;
			advanceWord();
		}

		@Override
		public boolean hasNext() {
			return wordIndex < tailStart || tailIndex < tailStart + tailCount;
		}

		@Override
		public int nextInt() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			int result;
			if (wordIndex < tailStart) {
				int bit = numberOfTrailingZeros(bits);
				result = (wordIndex << 5) + bit;
				bits &= bits - 1;
				if (bits == 0) {
					advanceWord();
				}
			} else {
				result = data[tailIndex++];
			}
			lastReturnedIndex = result;
			canRemove = true;
			return result;
		}

		@Override
		public void remove() {
			if (!canRemove) {
				throw new IllegalStateException("remove() must follow next()");
			}
			int oldTailStart = tailStart;
			boolean wasTail = (lastReturnedIndex >>> 5) >= oldTailStart;
			IntSet.this.removeCore(lastReturnedIndex);
			if (wasTail) {
				// The cursor had already moved past the returned value.
				tailIndex--;
			} else {
				int removedWords = oldTailStart - tailStart;
				if (removedWords > 0) {
					// The tail moved left when the top words were removed.
					tailIndex -= removedWords;
					if (wordIndex >= tailStart) {
						wordIndex = tailStart;
						bits = 0;
					}
				}
			}
			canRemove = false;
		}

		private void advanceWord() {
			while (wordIndex < tailStart && bits == 0) {
				wordIndex++;
				if (wordIndex < tailStart) {
					bits = data[wordIndex];
				}
			}
		}
	}

	private static final int[] EMPTY = new int[0];
}
