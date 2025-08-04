module works.bosk.spring.boot {
	requires com.fasterxml.jackson.databind;
	requires org.apache.tomcat.embed.core;
	requires org.slf4j;
	requires spring.boot.autoconfigure;
	requires spring.boot;
	requires spring.context;
	requires spring.web;
	requires works.bosk.core;
	requires works.bosk.jackson;

	requires static lombok;

	exports works.bosk.spring.boot;
}
