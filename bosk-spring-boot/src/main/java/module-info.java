module works.bosk.spring.boot {
	requires transitive com.fasterxml.jackson.databind;
	requires transitive org.apache.tomcat.embed.core;
	requires org.slf4j;
	requires spring.boot.autoconfigure;
	requires transitive spring.boot;
	requires transitive spring.context;
	requires transitive spring.web;
	requires transitive works.bosk.core;
	requires transitive works.bosk.jackson;

	requires static lombok;

	exports works.bosk.spring.boot;
}
