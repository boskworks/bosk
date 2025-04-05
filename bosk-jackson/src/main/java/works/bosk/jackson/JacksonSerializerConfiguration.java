package works.bosk.jackson;

public record JacksonSerializerConfiguration(
) {
	public static JacksonSerializerConfiguration defaultConfiguration() {
		return new JacksonSerializerConfiguration();
	}
}
