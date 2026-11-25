package works.bosk.drivers.mongo.status;

public record StateStatus(
	Long sizeInBytes,
	Difference difference
) { }
