package works.bosk.hello.state;

import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.StateTreeNode;

/**
 * The root of the {@link Bosk} state tree.
 */
public record BoskState(
	Catalog<Target> targets
) implements StateTreeNode { }
