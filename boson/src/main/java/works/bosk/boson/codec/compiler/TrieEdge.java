package works.bosk.boson.codec.compiler;

public record TrieEdge(int codePoint, TrieNode child) {
	@Override
	public String toString() {
		String name;
		if (Character.isJavaIdentifierPart(codePoint)) {
			name = Character.toString(codePoint);
		} else {
			name = Character.getName(codePoint);
		}
		return name + child;
	}
}
