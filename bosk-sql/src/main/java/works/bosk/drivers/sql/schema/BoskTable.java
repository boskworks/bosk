package works.bosk.drivers.sql.schema;

import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.TableImpl;

import static org.jooq.impl.DSL.name;
import static org.jooq.impl.SQLDataType.CHAR;
import static org.jooq.impl.SQLDataType.CLOB;
import static org.jooq.impl.SQLDataType.VARCHAR;

public class BoskTable extends TableImpl<Record> {
	public final TableField<Record, String> ID = createField(
		name("id"), CHAR(7));
	public final TableField<Record, String> EPOCH = createField(
		name("epoch"), VARCHAR.notNull());
	public final TableField<Record, String> STATE = createField(
		name("state"), CLOB.null_());

	BoskTable() {
		super(name("bosk_state"));
	}
}
