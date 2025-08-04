module works.bosk.jackson {
	requires transitive com.fasterxml.jackson.core;
	requires transitive com.fasterxml.jackson.databind;
	requires org.slf4j;
	requires transitive works.bosk.core;

	requires static lombok;

	exports works.bosk.jackson;
}
