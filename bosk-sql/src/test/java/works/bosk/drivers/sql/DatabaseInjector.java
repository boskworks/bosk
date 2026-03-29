package works.bosk.drivers.sql;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.junit.Injector;

record DatabaseInjector() implements Injector {
	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType == Database.class;
	}

	@Override
	public List<Database> values() {
		return List.of(Database.POSTGRES, Database.SQLITE);
	}
}
