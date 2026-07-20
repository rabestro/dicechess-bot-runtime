package lv.id.jc.dicechess.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class WebhookHandlerTest {

	private static final String SECRET = "test-webhook-secret";
	private static final long NOW = 1752750000L;
	private static final Gson GSON = new Gson();

	private static String stringField(String json, String name) {
		return GSON.fromJson(json, JsonObject.class).get(name).getAsString();
	}

	private static Map<String, String> signedHeaders(String body, long timestamp) {
		return Map.of(
				WebhookHandler.TIMESTAMP_HEADER, String.valueOf(timestamp),
				WebhookHandler.SIGNATURE_HEADER, Signatures.sign(SECRET, timestamp, body));
	}

	@Test
	void handshakeEchoesTheNonceWithoutASignature() {
		var handler = new WebhookHandler(SECRET, dfen -> List.of());

		var response = handler.handle(Map.of(), "{\"type\":\"verification\",\"nonce\":\"abc123\"}", NOW);

		assertThat(response.status()).isEqualTo(200);
		assertThat(stringField(response.jsonBody(), "nonce")).isEqualTo("abc123");
	}

	@Test
	void aSignedTurnRelaysTheStrategysMoves() {
		Function<String, List<String>> strategy = dfen -> List.of("e2e4", "e7e5");
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"some-dfen\"}}";

		var response = handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(response.status()).isEqualTo(200);
		var moves = GSON.fromJson(response.jsonBody(), JsonObject.class).getAsJsonArray("moves");
		assertThat(moves).hasSize(2);
		assertThat(moves.get(0).getAsString()).isEqualTo("e2e4");
	}

	@Test
	void theStrategyReceivesExactlyTheDfenFromTheEnvelope() {
		var seenDfen = new AtomicReference<String>();
		Function<String, List<String>> strategy = dfen -> {
			seenDfen.set(dfen);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"state\":{\"dfen\":\"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w NBK\"}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenDfen.get()).isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w NBK");
	}

	@Test
	void headerLookupIsCaseInsensitive() {
		var handler = new WebhookHandler(SECRET, dfen -> List.of());
		var body = "{\"type\":\"yourTurn\",\"state\":{\"dfen\":\"x\"}}";
		var headers = Map.of(
				"X-DiceChess-Timestamp", String.valueOf(NOW),
				"X-DiceChess-Signature", Signatures.sign(SECRET, NOW, body));

		assertThat(handler.handle(headers, body, NOW).status()).isEqualTo(200);
	}

	@Test
	void aMissingOrTamperedSignatureIsRejectedWith401() {
		var handler = new WebhookHandler(SECRET, dfen -> List.of());
		var body = "{\"type\":\"yourTurn\",\"state\":{\"dfen\":\"x\"}}";
		var tampered =
				Map.of(WebhookHandler.TIMESTAMP_HEADER, String.valueOf(NOW), WebhookHandler.SIGNATURE_HEADER, "deadbeef");

		assertThat(handler.handle(tampered, body, NOW).status()).isEqualTo(401);
		assertThat(handler.handle(Map.of(), body, NOW).status()).isEqualTo(401);
	}

	@Test
	void aStaleTimestampIsRejectedEvenWithAGenuineSignatureReplayGuard() {
		var handler = new WebhookHandler(SECRET, dfen -> List.of());
		var body = "{\"type\":\"yourTurn\",\"state\":{\"dfen\":\"x\"}}";
		var staleTimestamp = NOW - 3600;

		assertThat(handler.handle(signedHeaders(body, staleTimestamp), body, NOW).status()).isEqualTo(401);
	}

	@Test
	void garbageJsonAndAMissingDfenAre400NeverAnException() {
		var handler = new WebhookHandler(SECRET, dfen -> List.of());
		assertThat(handler.handle(Map.of(), "not json at all", NOW).status()).isEqualTo(400);

		var noDfen = "{\"type\":\"yourTurn\",\"state\":{}}";
		assertThat(handler.handle(signedHeaders(noDfen, NOW), noDfen, NOW).status()).isEqualTo(400);
	}

	@Test
	void anUnrecognizedTypeIs400() {
		var handler = new WebhookHandler(SECRET, dfen -> List.of());
		assertThat(handler.handle(Map.of(), "{\"type\":\"somethingElse\"}", NOW).status()).isEqualTo(400);
	}

	@Test
	void aStrategyThatThrowsIs500NotAnException() {
		Function<String, List<String>> strategy = dfen -> {
			throw new RuntimeException("boom");
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"state\":{\"dfen\":\"x\"}}";

		assertThat(handler.handle(signedHeaders(body, NOW), body, NOW).status()).isEqualTo(500);
	}

	@Test
	void aStrategyThatReturnsNullIsTreatedAsNoMoves() {
		Function<String, List<String>> strategy = dfen -> null;
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"state\":{\"dfen\":\"x\"}}";

		var response = handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(response.status()).isEqualTo(200);
		assertThat(GSON.fromJson(response.jsonBody(), JsonObject.class).getAsJsonArray("moves")).isEmpty();
	}
}
