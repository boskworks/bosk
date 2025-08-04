module works.bosk.jackson {
	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;
	requires org.slf4j;
	requires works.bosk.core;

	requires static lombok;

	exports works.bosk.jackson;
}
