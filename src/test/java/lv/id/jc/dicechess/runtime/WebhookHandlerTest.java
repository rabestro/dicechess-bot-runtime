package lv.id.jc.dicechess.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
		var handler = new WebhookHandler(SECRET, ctx -> List.of());

		var response = handler.handle(Map.of(), "{\"type\":\"verification\",\"nonce\":\"abc123\"}", NOW);

		assertThat(response.status()).isEqualTo(200);
		assertThat(stringField(response.jsonBody(), "nonce")).isEqualTo("abc123");
	}

	@Test
	void aSignedTurnRelaysTheStrategysMoves() {
		Function<TurnContext, List<String>> strategy = ctx -> List.of("e2e4", "e7e5");
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"some-dfen\"}}";

		var response = handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(response.status()).isEqualTo(200);
		var moves = GSON.fromJson(response.jsonBody(), JsonObject.class).getAsJsonArray("moves");
		assertThat(moves).hasSize(2);
		assertThat(moves.get(0).getAsString()).isEqualTo("e2e4");
	}

	@Test
	void theStrategyReceivesGameIdAndDfenFromTheEnvelope() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body =
				"{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w NBK\"}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().gameId()).isEqualTo("g1");
		assertThat(seenContext.get().dfen()).isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w NBK");
	}

	@Test
	void theStrategyReceivesItsOwnClockAsRemainingAndTheOpponentsAsOpponentRemaining() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"Black\",\"state\":{\"dfen\":\"x\","
				+ "\"clocks\":{\"white\":295000,\"black\":300000}}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().remainingMillis()).isEqualTo(300000L);
		assertThat(seenContext.get().opponentRemainingMillis()).isEqualTo(295000L);
	}

	@Test
	void anUntimedGameLeavesBothClockFieldsNull() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().remainingMillis()).isNull();
		assertThat(seenContext.get().opponentRemainingMillis()).isNull();
	}

	@Test
	void aFischerControlSurfacesThePerTurnIncrementInMillis() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\","
				+ "\"timeControl\":{\"Fischer\":{\"initialSeconds\":300,\"incrementSeconds\":3}}}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().incrementMillis()).isEqualTo(3000L);
	}

	@Test
	void aNonFischerControlLeavesTheIncrementNull() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\","
				+ "\"timeControl\":{\"SuddenDeath\":{\"initialSeconds\":60}}}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().incrementMillis()).isNull();
	}

	@Test
	void anAbsentTimeControlLeavesTheIncrementNull() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().incrementMillis()).isNull();
	}

	@Test
	void anInlineLegalMovesTreeIsFlattenedIntoCompletePaths() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\","
				+ "\"legalMoves\":{\"e2e4\":{\"g1f3\":{},\"b1c3\":{}},\"d2d4\":{\"d4d5\":{}}}}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().legalMoves())
				.containsExactlyInAnyOrder(List.of("e2e4", "g1f3"), List.of("e2e4", "b1c3"), List.of("d2d4", "d4d5"));
	}

	@Test
	void anEmptyLegalMovesTreeIsAGenuineEmptyListNotNull() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\",\"legalMoves\":{}}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().legalMoves()).isNotNull().isEmpty();
	}

	@Test
	void anAbsentLegalMovesFieldIsNull() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().legalMoves()).isNull();
	}

	@Test
	void aCappedLegalMovesTreeWithNoBaseUrlConfiguredIsNull() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		var handler = new WebhookHandler(SECRET, strategy); // 2-arg: no fallback capability
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\",\"legalMoves\":null}}";

		handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(seenContext.get().legalMoves()).isNull();
	}

	@Test
	void aCappedLegalMovesTreeFetchesTheFallbackWhenABaseUrlIsConfigured() throws IOException {
		var fallbackBody = "{\"version\":4,\"dfen\":\"x\",\"dicePending\":true,\"legalMoves\":{\"e2e4\":{\"g1f3\":{}}}}";
		var server = stubMovesEndpoint("g1", fallbackBody);
		try {
			var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			var seenContext = new AtomicReference<TurnContext>();
			Function<TurnContext, List<String>> strategy = ctx -> {
				seenContext.set(ctx);
				return List.of();
			};
			var handler = new WebhookHandler(SECRET, baseUrl, strategy);
			var body =
					"{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\",\"legalMoves\":null}}";

			handler.handle(signedHeaders(body, NOW), body, NOW);

			assertThat(seenContext.get().legalMoves()).containsExactly(List.of("e2e4", "g1f3"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aFailedFallbackFetchDegradesToNullNotAnException() {
		var seenContext = new AtomicReference<TurnContext>();
		Function<TurnContext, List<String>> strategy = ctx -> {
			seenContext.set(ctx);
			return List.of();
		};
		// Port 1: nothing listens there, so the connection is refused immediately.
		var handler = new WebhookHandler(SECRET, "http://127.0.0.1:1", strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\",\"legalMoves\":null}}";

		var response = handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(response.status()).isEqualTo(200);
		assertThat(seenContext.get().legalMoves()).isNull();
	}

	private static HttpServer stubMovesEndpoint(String gameId, String responseBody) throws IOException {
		var server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/games/" + gameId + "/moves", exchange -> {
			var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		return server;
	}

	@Test
	void headerLookupIsCaseInsensitive() {
		var handler = new WebhookHandler(SECRET, ctx -> List.of());
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";
		var headers = Map.of(
				"X-DiceChess-Timestamp", String.valueOf(NOW),
				"X-DiceChess-Signature", Signatures.sign(SECRET, NOW, body));

		assertThat(handler.handle(headers, body, NOW).status()).isEqualTo(200);
	}

	@Test
	void aMissingOrTamperedSignatureIsRejectedWith401() {
		var handler = new WebhookHandler(SECRET, ctx -> List.of());
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";
		var tampered =
				Map.of(WebhookHandler.TIMESTAMP_HEADER, String.valueOf(NOW), WebhookHandler.SIGNATURE_HEADER, "deadbeef");

		assertThat(handler.handle(tampered, body, NOW).status()).isEqualTo(401);
		assertThat(handler.handle(Map.of(), body, NOW).status()).isEqualTo(401);
	}

	@Test
	void aStaleTimestampIsRejectedEvenWithAGenuineSignatureReplayGuard() {
		var handler = new WebhookHandler(SECRET, ctx -> List.of());
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";
		var staleTimestamp = NOW - 3600;

		assertThat(handler.handle(signedHeaders(body, staleTimestamp), body, NOW).status()).isEqualTo(401);
	}

	@Test
	void garbageJsonAndAMissingDfenAre400NeverAnException() {
		var handler = new WebhookHandler(SECRET, ctx -> List.of());
		assertThat(handler.handle(Map.of(), "not json at all", NOW).status()).isEqualTo(400);

		var noDfen = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{}}";
		assertThat(handler.handle(signedHeaders(noDfen, NOW), noDfen, NOW).status()).isEqualTo(400);
	}

	@Test
	void aMissingGameIdOrSeatIs400() {
		var handler = new WebhookHandler(SECRET, ctx -> List.of());
		var noGameId = "{\"type\":\"yourTurn\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";
		var noSeat = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"state\":{\"dfen\":\"x\"}}";

		assertThat(handler.handle(signedHeaders(noGameId, NOW), noGameId, NOW).status()).isEqualTo(400);
		assertThat(handler.handle(signedHeaders(noSeat, NOW), noSeat, NOW).status()).isEqualTo(400);
	}

	@Test
	void anUnrecognizedTypeIs400() {
		var handler = new WebhookHandler(SECRET, ctx -> List.of());
		assertThat(handler.handle(Map.of(), "{\"type\":\"somethingElse\"}", NOW).status()).isEqualTo(400);
	}

	@Test
	void aStrategyThatThrowsIs500NotAnException() {
		Function<TurnContext, List<String>> strategy = ctx -> {
			throw new RuntimeException("boom");
		};
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";

		assertThat(handler.handle(signedHeaders(body, NOW), body, NOW).status()).isEqualTo(500);
	}

	@Test
	void aStrategyThatReturnsNullIsTreatedAsNoMoves() {
		Function<TurnContext, List<String>> strategy = ctx -> null;
		var handler = new WebhookHandler(SECRET, strategy);
		var body = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";

		var response = handler.handle(signedHeaders(body, NOW), body, NOW);

		assertThat(response.status()).isEqualTo(200);
		assertThat(GSON.fromJson(response.jsonBody(), JsonObject.class).getAsJsonArray("moves")).isEmpty();
	}
}
