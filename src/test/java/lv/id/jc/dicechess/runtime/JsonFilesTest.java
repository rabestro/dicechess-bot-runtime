package lv.id.jc.dicechess.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFilesTest {

	@Test
	void aMissingFileDegradesToAnEmptyMap() {
		assertThat(JsonFiles.loadStringMap(Path.of("no-such-file.json"))).isEmpty();
	}

	@Test
	void aRealFileParsesIntoItsEntries(@TempDir Path tempDir) throws IOException {
		var path = tempDir.resolve("book.json");
		Files.writeString(path, "{\"key-a\":\"e2e4\",\"key-b\":\"d2d4\"}");

		assertThat(JsonFiles.loadStringMap(path)).containsExactly(entry("key-a", "e2e4"), entry("key-b", "d2d4"));
	}

	@Test
	void malformedJsonIsRejectedNotSilentlyEmptied(@TempDir Path tempDir) throws IOException {
		var path = tempDir.resolve("garbage.json");
		Files.writeString(path, "not json at all");

		assertThatThrownBy(() -> JsonFiles.loadStringMap(path)).isInstanceOf(IllegalArgumentException.class);
	}
}
