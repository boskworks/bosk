package works.bosk.drivers.mongo.internal;

import java.util.Optional;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;


/**
 * The role a write plays in its transaction, which the writer records in the
 * {@code writeId} field of the document it writes. The receiver reads the role
 * from each change event to find the transaction's last write and to gather
 * the documents that make up the replaced state.
 */
enum WriteRole {
	/**
	 * No marker, and none needed. A {@code DELETE} cannot carry one, because
	 * MongoDB applies no field changes to the deleted document.
	 */
	UNMARKED(null),

	/**
	 * The transaction's last write, which the writer makes the topmost
	 * document's write. The receiver uses it to know when the transaction's
	 * event tree is complete, and treats it as the topmost.
	 */
	LAST("last:"),

	/**
	 * A state document written before the transaction's last write. Its full
	 * document is one of the parts gathered to assemble the replaced state.
	 */
	PART("part:"),

	/**
	 * A pre-delete: an update that removes a field so the later write of the
	 * same document is visible in the change stream. It is never the last
	 * write.
	 */
	PDEL("pdel:");

	private final @Nullable String prefix;

	WriteRole(@Nullable String prefix) {
		this.prefix = prefix;
	}

	/**
	 * A new marker for a write in this role. The receiver only reads the prefix;
	 * the rest of the value just makes each write distinguishable.
	 */
	BsonString newWriteId() {
		if (prefix == null) {
			throw new AssertionError("UNMARKED writes carry no writeId");
		}
		return new BsonString(prefix + new ObjectId().toHexString());
	}

	/**
	 * @return the prefix this role writes at the start of a {@code writeId}
	 */
	String prefix() {
		if (prefix == null) {
			throw new AssertionError("UNMARKED writes carry no writeId");
		}
		return prefix;
	}

	/**
	 * @return the role encoded by {@code writeId}, or empty if it is absent or
	 * has no recognized prefix
	 */
	static Optional<WriteRole> of(BsonValue writeId) {
		if (writeId instanceof BsonString s) {
			for (WriteRole role : values()) {
				if (role.prefix != null && s.getValue().startsWith(role.prefix)) {
					return Optional.of(role);
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public String toString() {
		return prefix == null ? name() : prefix;
	}
}
