package works.bosk.hello.state;

import works.bosk.Entity;
import works.bosk.Identifier;

/**
 * Someone to be greeted.
 */
public record Target(
	Identifier id
) implements Entity { }
