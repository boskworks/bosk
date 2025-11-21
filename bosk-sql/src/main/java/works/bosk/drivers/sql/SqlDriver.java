package works.bosk.drivers.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BiFunction;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.jackson.JacksonSerializer;

public interface SqlDriver extends BoskDriver {
	/**
	 * @param objectMapperCustomizer provides an opportunity for the caller to customize the internally-created {@link ObjectMapper}.
	 */
	static <RR extends StateTreeNode> SqlDriverFactory<RR> factory(
		SqlDriverSettings settings,
		ConnectionSource connectionSource,
		BiFunction<BoskInfo<RR>, JsonMapper.Builder, JsonMapper.Builder> objectMapperCustomizer
	) {
		return (b, d) -> {
			JacksonSerializer jacksonSerializer = new JacksonSerializer();
			ObjectMapper mapper = objectMapperCustomizer.apply(b, JsonMapper.builder().addModule(jacksonSerializer.moduleFor(b))).build();
			return new SqlDriverFacade(jacksonSerializer, new SqlDriverImpl(
				settings, connectionSource, b, mapper, d
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
