package works.bosk.drivers.sql.schema;

import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.TableImpl;

import static org.jooq.impl.DSL.name;
import static org.jooq.impl.SQLDataType.BIGINT;
import static org.jooq.impl.SQLDataType.CLOB;
import static org.jooq.impl.SQLDataType.VARCHAR;

public class ChangesTable extends TableImpl<org.jooq.Record> {
	/**
	 * A UUID chosen at the time the tables were created.
	 * Provides a scope for {@link #REVISION}.
	 * Revisions from different epochs are not comparable.
	 */
	public final TableField<Record, String> EPOCH = createField(
		name("epoch"), VARCHAR.notNull());
	public final TableField<Record, Long> REVISION = createField(
		name("revision"), BIGINT.null_().identity(true));
	public final TableField<Record, String> REF = createField(
		name("ref"), VARCHAR.notNull());
	public final TableField<Record, String> NEW_STATE = createField(
		name("new_state"), CLOB.null_());
	public final TableField<Record, String> DIAGNOSTICS = createField(
		name("diagnostics"), CLOB.notNull());
	public final TableField<Record, String> TENANT = createField(
		name("tenant"), VARCHAR.null_());

	ChangesTable() {
		super(name("bosk_changes"));
	}
}
