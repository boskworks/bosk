package works.bosk.drivers.mongo;

import java.util.Collection;
import works.bosk.Catalog;
import works.bosk.ListValue;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;

import static java.util.Arrays.asList;

/**
 * A scalable format that stores the bosk state in multiple documents,
 * thereby overcoming MongoDB's 16MB document size limit.
 * <p>
 * Reconstructing the state tree from the documents is not dependent
 * on the graft point configuration because the documents are self-describing:
 * whatever way the state happens to be divided into documents,
 * there is an unambiguous way to reconstruct the full state tree.
 * However, modifying the state requires correct graft point configuration,
 * or else different updates to the same node could end up in different documents,
 * leading to inconsistencies and contradictions.
 * <p>
 * Named after Pando: looks like a forest, but it's actually one tree with one root system.
 *
 * @param graftPoints A list of path strings pointing to {@link Catalog Catalog}
 *                   or {@link SideTable SideTable} tree nodes whose entries (children)
 *                   are to be stored in their own documents.
 */
public record PandoFormat(
	// TODO: Since this is used for updates and not reads, it should probably be called prunePoints or something
	ListValue<String> graftPoints
) implements StateTreeNode, MongoDriverSettings.DatabaseFormat {
	/**
	 * Differs from Sequoia in that (1) the root document has a different ID, rendering the formats incompatible;
	 * and (2) Sequoia is designed not to need multi-document transactions.
	 */
	public static PandoFormat oneBigDocument() {
		return new PandoFormat(ListValue.empty());
	}

	public static PandoFormat withGraftPoints(Collection<String> pathStrings) {
		return new PandoFormat(ListValue.from(pathStrings));
	}

	public static PandoFormat withGraftPoints(String... pathStrings) {
		return withGraftPoints(asList(pathStrings));
	}
}
