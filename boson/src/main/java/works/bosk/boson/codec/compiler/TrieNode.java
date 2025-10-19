package works.bosk.boson.codec.compiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.stream.Collectors.joining;

public sealed interface TrieNode {
	/**
	 * @param completeString the string matched by this trie node
	 * @param matchedPrefix the portion of the string consumed by traversing the trie to get to this node.
	 *                      The character at {@code completeString[matchedPrefix]} is the final character
	 *                      required to distinguish this node from all other nodes in the trie;
	 *                      all remaining characters in {@code completeString} are redundant.
	 */
	record LeafNode(String completeString, int matchedPrefix) implements TrieNode {
		@Override
		public String toString() {
			return ":" + completeString.substring(0, matchedPrefix) + "·" + completeString.substring(matchedPrefix);
		}
	}

	/**
	 * This is not designed for fast lookups. We assume it will be
	 * used in a compiler, which must handle all cases, rather than
	 * in an interpreter, which must look up one particular case.
	 */
	record ChoiceNode(List<TrieEdge> edges) implements TrieNode {
		public ChoiceNode(TrieEdge... edges) {
			this(List.of(edges));
		}

		@Override
		public String toString() {
			return "[" + edges.stream()
				.map(TrieEdge::toString)
				.collect(joining(",")) + "]";
		}
	}

	static TrieNode from(Collection<String> memberNames) {
		assert !memberNames.isEmpty();
		return nodeFor(memberNames.stream()
			.sorted()
			.toList(), 0);
	}

	private static TrieNode nodeFor(List<String> list, int commonPrefixLength) {
		assert !list.isEmpty();
		assert list.getFirst().substring(0, commonPrefixLength)
			.equals(list.getLast().substring(0, commonPrefixLength));
		if (list.size() == 1) {
			return new TrieNode.LeafNode(list.getFirst(), commonPrefixLength);
		}

		List<TrieEdge> edges = new ArrayList<>();

		// With at least two candidates remaining, we need to check the next character
		for (int i = 0; i < list.size(); ) {
			int firstIndex = i;
			int cp = list.get(i).codePointAt(commonPrefixLength);
			int charCount = Character.charCount(cp);
			while (++i < list.size()) {
				if (list.get(i).codePointAt(commonPrefixLength) != cp) {
					break;
				}
			}
			var child = nodeFor(list.subList(firstIndex, i), commonPrefixLength + charCount);
			edges.add(new TrieEdge(cp, child));
		}

		return new TrieNode.ChoiceNode(List.copyOf(edges));
	}

}
