package works.bosk.drivers.sql.schema;

import java.sql.Connection;
import java.sql.SQLException;
import org.jooq.Record;
import org.jooq.TableField;

import static org.jooq.impl.DSL.using;

public class Schema {
	public final BoskTable BOSK = new BoskTable();
	public final ChangesTable CHANGES = new ChangesTable();

	// Unqualified field names for convenience

	public final TableField<Record, String> ID = BOSK.ID;
	public final TableField<Record, String> STATE = BOSK.STATE;
	public final TableField<Record, Long>   REVISION = CHANGES.REVISION;
	public final TableField<Record, String> REF = CHANGES.REF;
	public final TableField<Record, String> NEW_STATE = CHANGES.NEW_STATE;
	public final TableField<Record, String> DIAGNOSTICS = CHANGES.DIAGNOSTICS;
	public final TableField<Record, String> TENANT = CHANGES.TENANT;

	public void dropTables(Connection c) throws SQLException {
		using(c)
			.dropTableIfExists(BOSK)
			.execute();
		using(c)
			.dropTableIfExists(CHANGES)
			.execute();
		c.commit();
	}
}
