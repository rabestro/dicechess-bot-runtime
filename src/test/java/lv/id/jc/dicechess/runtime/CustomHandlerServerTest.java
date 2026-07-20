package lv.id.jc.dicechess.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the whole stack end to end: a real socket, a real HTTP client, no mocks. */
class CustomHandlerServerTest {

	private static final String SECRET = "test-webhook-secret";
	private static final Gson GSON = new Gson();

	@Test
	void handshakeAndASignedTurnOverRealHttp() throws Exception {
		var handler = new WebhookHandler(SECRET, dfen -> List.of("b1c3"));
		var server = CustomHandlerServer.start(0, "/api/webhook", handler);
		try {
			var base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhook";
			var client = HttpClient.newHttpClient();

			var handshakeBody = "{\"type\":\"verification\",\"nonce\":\"live-1\"}";
			var handshake = client.send(
					HttpRequest.newBuilder(URI.create(base))
							.POST(HttpRequest.BodyPublishers.ofString(handshakeBody))
							.build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(handshake.statusCode()).isEqualTo(200);
			assertThat(GSON.fromJson(handshake.body(), JsonObject.class).get("nonce").getAsString())
					.isEqualTo("live-1");

			var turnBody = "{\"type\":\"yourTurn\",\"gameId\":\"g1\",\"seat\":\"White\",\"state\":{\"dfen\":\"x\"}}";
			var now = System.currentTimeMillis() / 1000;
			var turn = client.send(
					HttpRequest.newBuilder(URI.create(base))
							.header(WebhookHandler.TIMESTAMP_HEADER, String.valueOf(now))
							.header(WebhookHandler.SIGNATURE_HEADER, Signatures.sign(SECRET, now, turnBody))
							.POST(HttpRequest.BodyPublishers.ofString(turnBody))
							.build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(turn.statusCode()).isEqualTo(200);
			assertThat(GSON.fromJson(turn.body(), JsonObject.class)
							.getAsJsonArray("moves")
							.get(0)
							.getAsString())
					.isEqualTo("b1c3");
		} finally {
			server.stop(0);
		}
	}
}
