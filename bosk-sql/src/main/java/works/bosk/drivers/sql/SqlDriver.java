package works.bosk.drivers.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BiFunction;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.jackson.JacksonPlugin;

public interface SqlDriver extends BoskDriver {
	/**
	 * @param objectMapperCustomizer provides an opportunity for the caller to customize the internally-created {@link ObjectMapper}.
	 */
	static <RR extends StateTreeNode> SqlDriverFactory<RR> factory(
		SqlDriverSettings settings,
		ConnectionSource connectionSource,
		BiFunction<BoskInfo<RR>, ObjectMapper, ObjectMapper> objectMapperCustomizer
	) {
		return (b, d) -> {
			JacksonPlugin jacksonPlugin = new JacksonPlugin();
			ObjectMapper mapper = objectMapperCustomizer.apply(b, new ObjectMapper().registerModule(jacksonPlugin.moduleFor(b)));
			return new SqlDriverFacade(new SqlDriverImpl(
				settings, connectionSource, b, jacksonPlugin, mapper, d
			));
		};
	}

	void close();

	interface ConnectionSource {
		Connection get() throws SQLException;
	}

	interface SqlDriverFactory<RR extends StateTreeNode> extends DriverFactory<RR> {
		@Override SqlDriver build(BoskInfo<RR> boskInfo, BoskDriver downstream);
	}
}
