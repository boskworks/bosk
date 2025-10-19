package works.bosk.boson.codec.compiler;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.boson.codec.compiler.TrieNode.ChoiceNode;
import works.bosk.boson.codec.compiler.TrieNode.LeafNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrieNodeTest {

	@Test
	void test() {
		TrieNode actual = TrieNode.from(List.of("m10", "m20"));
		TrieNode expected = new ChoiceNode(
			new TrieEdge('m', new ChoiceNode(
				new TrieEdge('1', new LeafNode("m10", 2)),
				new TrieEdge('2', new LeafNode("m20", 2))
			))
		);
		assertEquals(expected, actual);
	}

}
