package works.bosk.drivers.mongo.internal;

public class BsonFormatException extends IllegalStateException {
	public BsonFormatException(String s) { super(s); }
	BsonFormatException(String message, Throwable cause) { super(message, cause); }
	BsonFormatException(Throwable cause) { super(cause); }
}
