package works.bosk.internal;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A list that only ever grows, and that several threads can append to and read
 * without any of them holding a lock for the read.
 *
 * <p>
 * Appends are serialised by a private monitor: assigning the next index and writing
 * the element have to happen together, and a resize copies the array. Reads take
 * no lock at all: they read {@link #size} first and {@link #entries} second, so
 * the array they get is always long enough to index up to the size they saw. That
 * ordering matters, because a resize writes the new array before the size that
 * needs it; reading the fields the other way round could index past the end of an
 * array that has since been replaced.
 *
 * <p>
 * An append writes at exactly {@code size}, which is beyond the last element any
 * reader could be looking at, and a resize copies to a fresh array that is
 * never written below the size it is used for. So the prefix below any size a
 * reader can observe never changes, and the volatile write to {@link #size} is
 * what makes the elements in it visible.
 *
 * <p>
 * Iterators, list iterators and spliterators capture the size and the array when
 * they are created. They therefore see the elements present at that moment and
 * nothing appended later, and they never throw
 * {@link java.util.ConcurrentModificationException}.
 *
 * <p>
 * Everything that would rewrite the list in place is unsupported:
 * {@link #add(E) add} is the only mutation. Null elements are not allowed.
 */
public final class ConcurrentAppendOnlyList<E> extends AbstractList<E> implements RandomAccess {
	/**
	 * The number of elements. Volatile because an append writes it after the
	 * element, which is what makes the new element visible to readers, and because
	 * it tells a reader how far it may index.
	 * <p>
	 * Read this before {@link #entries} when you need both: a resize writes the
	 * array before the size that needs it, so a size is a promise that the array
	 * you read next is long enough for it.
	 */
	private volatile int size;

	/**
	 * The elements, at indices below {@link #size}. Volatile because a resize
	 * replaces it: a reader that sees a size is guaranteed to see an array long
	 * enough for it.
	 */
	private volatile Object[] entries = NO_ELEMENTS;

	/**
	 * Serialises appends, which have to choose an index and write it as one step.
	 * Reads never take it.
	 */
	private final Object appendMonitor = new Object();

	/**
	 * True for a sublist view: it shares its parent's array, so an append would
	 * overwrite an element of the parent.
	 */
	private final boolean view;

	public ConcurrentAppendOnlyList() {
		this.view = false;
	}

	private ConcurrentAppendOnlyList(Object[] entries, int size) {
		this.entries = entries;
		this.size = size;
		this.view = true;
	}

	/**
	 * Creates a list containing the given elements, in iteration order.
	 */
	public ConcurrentAppendOnlyList(Collection<? extends E> elements) {
		this.view = false;
		for (E element : elements) {
			add(element);
		}
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public E get(int index) {
		return elementAt(Objects.checkIndex(index, size));
	}

	@Override
	public boolean add(E element) {
		if (view) {
			throw appendOnly();
		}
		Objects.requireNonNull(element, "element");
		synchronized (appendMonitor) {
			int n = size;
			accommodate(n, 1);
			entries[n] = element;
			size = n + 1;
		}
		return true;
	}

	/**
	 * Unsupported, even for an index equal to {@link #size()}. Honouring the index
	 * would mean reserving it, and two threads appending at once would then have
	 * to make one of them fail; a caller that wants to append can say so with
	 * {@link #add(Object)}.
	 */
	@Override
	public void add(int index, E element) {
		throw appendOnly();
	}

	/**
	 * Appends all the given elements, as one indivisible step: a reader sees all
	 * of them or none.
	 */
	@Override
	public boolean addAll(Collection<? extends E> elements) {
		if (view) {
			throw appendOnly();
		}
		Objects.requireNonNull(elements, "elements");
		for (E element : elements) {
			Objects.requireNonNull(element, "element");
		}
		if (elements.isEmpty()) {
			return false;
		}
		synchronized (appendMonitor) {
			int n = size;
			int count = elements.size();
			accommodate(n, count);
			for (E element : elements) {
				entries[n++] = element;
			}
			size = n;
		}
		return true;
	}

	/**
	 * Ensures {@link #entries} can hold {@code count} more elements from
	 * {@code from} on. Only ever called while holding {@link #appendMonitor}.
	 */
	private void accommodate(int from, int count) {
		long required = (long) from + count;
		if (required > Integer.MAX_VALUE) {
			throw new IllegalStateException("List is full");
		}
		Object[] a = entries;
		if (required > a.length) {
			long newLength = Math.max(INITIAL_CAPACITY, (long) a.length * 2);
			newLength = Math.max(newLength, required);
			a = Arrays.copyOf(a, (int) Math.min(newLength, Integer.MAX_VALUE));
			entries = a;
		}
	}

	@SuppressWarnings("unchecked")
	private E elementAt(int index) {
		return (E) entries[index];
	}

	@Override
	public Object[] toArray() {
		int limit = size;
		return Arrays.copyOf(entries, limit);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T[] toArray(T[] into) {
		Objects.requireNonNull(into, "into");
		int limit = size;
		Object[] source = entries;
		if (into.length < limit) {
			T[] result = (T[]) Array.newInstance(into.getClass().getComponentType(), limit);
			System.arraycopy(source, 0, result, 0, limit);
			return result;
		}
		System.arraycopy(source, 0, into, 0, limit);
		if (into.length > limit) {
			into[limit] = null;
		}
		return into;
	}

	@Override
	public Iterator<E> iterator() {
		return listIterator();
	}

	@Override
	public ListIterator<E> listIterator() {
		return listIterator(0);
	}

	@Override
	public ListIterator<E> listIterator(int index) {
		int limit = size;
		if (index < 0 || index > limit) {
			throw new IndexOutOfBoundsException("Index: " + index + ", size: " + limit);
		}
		Object[] snapshot = entries;
		return new ListIterator<>() {
			private int cursor = index;

			@Override
			public boolean hasNext() {
				return cursor < limit;
			}

			@Override
			public E next() {
				if (cursor >= limit) {
					throw new NoSuchElementException();
				}
				@SuppressWarnings("unchecked")
				E element = (E) snapshot[cursor];
				cursor++;
				return element;
			}

			@Override
			public boolean hasPrevious() {
				return cursor > 0;
			}

			@Override
			public E previous() {
				if (cursor <= 0) {
					throw new NoSuchElementException();
				}
				cursor--;
				@SuppressWarnings("unchecked")
				E element = (E) snapshot[cursor];
				return element;
			}

			@Override
			public int nextIndex() {
				return cursor;
			}

			@Override
			public int previousIndex() {
				return cursor - 1;
			}

			@Override
			public void remove() {
				throw appendOnly();
			}

			@Override
			public void set(E element) {
				throw appendOnly();
			}

			@Override
			public void add(E element) {
				throw appendOnly();
			}
		};
	}

	@Override
	public Spliterator<E> spliterator() {
		int limit = size;
		Object[] snapshot = entries;
		return new SnapshotSpliterator<>(snapshot, 0, limit);
	}

	@Override
	public E set(int index, E element) {
		throw appendOnly();
	}

	@Override
	public E remove(int index) {
		throw appendOnly();
	}

	@Override
	public boolean remove(Object element) {
		throw appendOnly();
	}

	@Override
	public boolean removeAll(Collection<?> elements) {
		throw appendOnly();
	}

	@Override
	public boolean retainAll(Collection<?> elements) {
		throw appendOnly();
	}

	@Override
	public boolean removeIf(Predicate<? super E> filter) {
		throw appendOnly();
	}

	@Override
	public void replaceAll(UnaryOperator<E> operator) {
		throw appendOnly();
	}

	@Override
	public void sort(Comparator<? super E> comparator) {
		throw appendOnly();
	}

	/**
	 * Returns a read-only snapshot of the first {@code toIndex} elements. Only a
	 * prefix is supported: a view starting partway in would need an offset on every
	 * read, and nothing needs one yet. The view refuses appends, because it shares
	 * this list's array and an append would write over an element of its parent.
	 */
	@Override
	public List<E> subList(int fromIndex, int toIndex) {
		int n = size;
		if (fromIndex < 0 || toIndex > n || fromIndex > toIndex) {
			throw new IndexOutOfBoundsException(
				"fromIndex: " + fromIndex + ", toIndex: " + toIndex + ", size: " + n);
		}
		if (fromIndex != 0) {
			throw new UnsupportedOperationException("Only a prefix has a sublist in this list");
		}
		return new ConcurrentAppendOnlyList<>(entries, toIndex);
	}

	@Override
	public void clear() {
		throw appendOnly();
	}

	private static UnsupportedOperationException appendOnly() {
		return new UnsupportedOperationException("This list is append-only");
	}

	private static final int INITIAL_CAPACITY = 8;

	/**
	 * Shared by every empty list. An append always grows {@link #entries} before
	 * writing anything, so no list writes into this array.
	 */
	private static final Object[] NO_ELEMENTS = new Object[0];

	/**
	 * Splits the range {@code [from, to)} of a captured array. Every split shares
	 * the same captured array, so the whole tree sees the same elements.
	 */
	private static final class SnapshotSpliterator<E> implements Spliterator<E> {
		private final Object[] snapshot;
		private int from;
		private final int to;

		SnapshotSpliterator(Object[] snapshot, int from, int to) {
			this.snapshot = snapshot;
			this.from = from;
			this.to = to;
		}

		@Override
		public boolean tryAdvance(Consumer<? super E> action) {
			Objects.requireNonNull(action, "action");
			if (from >= to) {
				return false;
			}
			@SuppressWarnings("unchecked")
			E element = (E) snapshot[from++];
			action.accept(element);
			return true;
		}

		@Override
		public void forEachRemaining(Consumer<? super E> action) {
			Objects.requireNonNull(action, "action");
			while (from < to) {
				@SuppressWarnings("unchecked")
				E element = (E) snapshot[from++];
				action.accept(element);
			}
		}

		@Override
		public Spliterator<E> trySplit() {
			int mid = (from + to) >>> 1;
			if (mid <= from) {
				return null;
			}
			SnapshotSpliterator<E> prefix = new SnapshotSpliterator<>(snapshot, from, mid);
			from = mid;
			return prefix;
		}

		@Override
		public long estimateSize() {
			return to - from;
		}

		@Override
		public int characteristics() {
			return ORDERED | SIZED | SUBSIZED | IMMUTABLE | NONNULL;
		}
	}
}
