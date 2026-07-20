package lv.id.jc.dicechess.runtime;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads a JSON object of string keys to string values from a file — the shape an opening book,
 * or any similarly simple lookup table, is exported as.
 */
public final class JsonFiles {

	private static final Gson GSON = new Gson();
	private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

	private JsonFiles() {}

	/**
	 * Loads {@code path} as a JSON object of string to string.
	 *
	 * @param path the file to load
	 * @return the parsed map, or an empty map if {@code path} does not exist
	 * @throws UncheckedIOException if {@code path} exists but cannot be read
	 * @throws IllegalArgumentException if {@code path}'s content is not a JSON object of strings
	 */
	public static Map<String, String> loadStringMap(Path path) {
		if (!Files.exists(path)) {
			return Map.of();
		}
		String json;
		try {
			json = Files.readString(path);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to read " + path, e);
		}
		try {
			Map<String, String> parsed = GSON.fromJson(json, STRING_MAP_TYPE);
			return parsed == null ? Map.of() : parsed;
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(path + " is not a JSON object of strings", e);
		}
	}
}
