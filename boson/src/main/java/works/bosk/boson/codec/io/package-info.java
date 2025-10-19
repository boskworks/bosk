/**
 * An efficient library for reading JSON text specifically.
 * The main abstraction is {@link works.bosk.boson.codec.JsonReader},
 * which provides an efficient way to read JSON tokens from a
 * {@link java.nio.channels.ReadableByteChannel}.
 * It knows just enough about JSON syntax to avoid doing unnecessary work,
 * but is not really meant to be used directly;
 * instead, use {@link works.bosk.boson.codec.Parser},
 * which calls this layer to do the IO but provides a much more useful API.
 */
package works.bosk.boson.codec.io;
