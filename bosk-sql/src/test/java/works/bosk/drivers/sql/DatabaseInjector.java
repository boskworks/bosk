package works.bosk.drivers.sql;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.junit.Injector;

import static works.bosk.drivers.sql.SqlTestService.Database.MYSQL;
import static works.bosk.drivers.sql.SqlTestService.Database.POSTGRES;
import static works.bosk.drivers.sql.SqlTestService.Database.SQLITE;

record DatabaseInjector() implements Injector {
	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType == Database.class;
	}

	@Override
	public List<Database> values() {
		return List.of(POSTGRES, SQLITE, MYSQL);
	}
}
