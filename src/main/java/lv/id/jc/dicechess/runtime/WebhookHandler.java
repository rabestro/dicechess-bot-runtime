package lv.id.jc.dicechess.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Orchestrates one webhook delivery: the ownership handshake, signature verification, and
 * dispatch to the bot's move-choosing function.
 *
 * <p>{@link #handle} never throws — every failure mode, including an exception from the
 * strategy function, becomes a {@link Response} with an appropriate status code, so a caller
 * can always write its result straight back to the HTTP client.
 */
public final class WebhookHandler {

	/** Header carrying the delivery's Unix-epoch-seconds timestamp. */
	public static final String TIMESTAMP_HEADER = "x-dicechess-timestamp";

	/** Header carrying the hex HMAC-SHA256 signature (see {@link Signatures}). */
	public static final String SIGNATURE_HEADER = "x-dicechess-signature";

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
	private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(5);

	private final String secret;
	private final String playApiBaseUrl;
	private final Function<TurnContext, List<String>> strategy;

	/**
	 * Creates a handler bound to one secret and one strategy, without the {@code GET
	 * /games/{id}/moves} fallback — {@link TurnContext#legalMoves()} will be {@code null} on the
	 * rare turn where the envelope's inline tree is capped.
	 *
	 * @param secret the webhook secret issued by the platform when the bot registered
	 * @param strategy chooses moves for a turn: a {@link TurnContext} in, a UCI move path out
	 */
	public WebhookHandler(String secret, Function<TurnContext, List<String>> strategy) {
		this(secret, null, strategy);
	}

	/**
	 * Creates a handler bound to one secret and one strategy, fetching {@code GET
	 * /games/{id}/moves} from {@code playApiBaseUrl} whenever the envelope's inline legal-move
	 * tree is capped — see {@link TurnContext#legalMoves()}. That endpoint is public, so no
	 * additional credential is needed.
	 *
	 * @param secret the webhook secret issued by the platform when the bot registered
	 * @param playApiBaseUrl play-api's base URL (e.g. {@code https://play-api.jc.id.lv}); a
	 *     trailing slash is tolerated
	 * @param strategy chooses moves for a turn: a {@link TurnContext} in, a UCI move path out
	 */
	public WebhookHandler(String secret, String playApiBaseUrl, Function<TurnContext, List<String>> strategy) {
		this.secret = secret;
		this.playApiBaseUrl = playApiBaseUrl == null ? null : stripTrailingSlash(playApiBaseUrl);
		this.strategy = strategy;
	}

	/**
	 * Handles one delivery.
	 *
	 * @param headers the request headers; lookup is case-insensitive, so any casing works
	 * @param rawBody the raw request body, exactly as received (the signature covers these
	 *     exact bytes)
	 * @param nowEpochSeconds the current time, Unix epoch seconds
	 * @return the response to send back — status 200 (handshake echoed, or moves chosen), 400
	 *     (unparseable or unrecognized body), 401 (missing, expired, or wrong signature), or 500
	 *     (the strategy function threw)
	 */
	public Response handle(Map<String, String> headers, String rawBody, long nowEpochSeconds) {
		JsonObject envelope;
		try {
			envelope = GSON.fromJson(rawBody, JsonObject.class);
			if (envelope == null || !envelope.has("type")) {
				return error(400, "missing \"type\"");
			}
		} catch (RuntimeException e) {
			return error(400, "malformed JSON body");
		}

		String type;
		try {
			type = envelope.get("type").getAsString();
		} catch (RuntimeException e) {
			return error(400, "malformed \"type\"");
		}

		try {
			return switch (type) {
				case "verification" -> handshake(envelope);
				case "yourTurn" -> yourTurn(headers, rawBody, envelope, nowEpochSeconds);
				default -> error(400, "unrecognized \"type\": " + type);
			};
		} catch (RuntimeException e) {
			return error(400, "malformed envelope");
		}
	}

	private Response handshake(JsonObject envelope) {
		var nonce = envelope.has("nonce") ? envelope.get("nonce").getAsString() : "";
		var body = new JsonObject();
		body.addProperty("nonce", nonce);
		return new Response(200, GSON.toJson(body));
	}

	private Response yourTurn(Map<String, String> headers, String rawBody, JsonObject envelope, long now) {
		var lowercased = lowercaseKeys(headers);
		var timestampHeader = lowercased.get(TIMESTAMP_HEADER);
		var signatureHeader = lowercased.get(SIGNATURE_HEADER);
		if (timestampHeader == null || signatureHeader == null) {
			return error(401, "missing signature headers");
		}

		long timestamp;
		try {
			timestamp = Long.parseLong(timestampHeader);
		} catch (NumberFormatException e) {
			return error(401, "malformed timestamp header");
		}

		if (!Signatures.verify(secret, timestamp, rawBody, signatureHeader, now)) {
			return error(401, "invalid or expired signature");
		}

		if (!envelope.has("gameId") || !envelope.has("seat")) {
			return error(400, "missing gameId or seat");
		}
		if (!envelope.has("state") || !envelope.getAsJsonObject("state").has("dfen")) {
			return error(400, "missing state.dfen");
		}
		var gameId = envelope.get("gameId").getAsString();
		var seat = envelope.get("seat").getAsString();
		var state = envelope.getAsJsonObject("state");
		var dfen = state.get("dfen").getAsString();

		Long remainingMillis = null;
		Long opponentRemainingMillis = null;
		if (state.has("clocks") && !state.get("clocks").isJsonNull()) {
			var clocks = state.getAsJsonObject("clocks");
			var white = clocks.get("white").getAsLong();
			var black = clocks.get("black").getAsLong();
			remainingMillis = seat.equals("White") ? white : black;
			opponentRemainingMillis = seat.equals("White") ? black : white;
		}

		List<List<String>> legalMoves = null;
		if (state.has("legalMoves")) {
			var legalMovesElement = state.get("legalMoves");
			if (legalMovesElement.isJsonNull()) {
				if (playApiBaseUrl != null) {
					legalMoves = fetchLegalMoves(gameId);
				}
			} else {
				legalMoves = flattenLegalMoves(legalMovesElement.getAsJsonObject());
			}
		}

		var context = new TurnContext(gameId, dfen, remainingMillis, opponentRemainingMillis, legalMoves);

		List<String> moves;
		try {
			moves = strategy.apply(context);
		} catch (RuntimeException e) {
			return error(500, "strategy failed: " + e.getMessage());
		}

		var body = new JsonObject();
		body.add("moves", GSON.toJsonTree(moves == null ? List.of() : moves));
		return new Response(200, GSON.toJson(body));
	}

	/**
	 * Fetches the fallback {@code GET /games/{gameId}/moves} and flattens its tree. Best-effort:
	 * any failure (network, non-200, malformed body) degrades to {@code null} rather than
	 * propagating, matching {@link #handle}'s never-throws contract.
	 */
	private List<List<String>> fetchLegalMoves(String gameId) {
		try {
			var uri = URI.create(playApiBaseUrl + "/games/" + gameId + "/moves");
			var request = HttpRequest.newBuilder(uri).timeout(FALLBACK_TIMEOUT).GET().build();
			var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return null;
			}
			var body = GSON.fromJson(response.body(), JsonObject.class);
			return flattenLegalMoves(body.getAsJsonObject("legalMoves"));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Walks the server's prefix tree of UCI micro-moves (each key a move, each value the tree of
	 * legal continuations, a leaf {@code {}} marking a complete turn) into the list of complete
	 * root-to-leaf paths.
	 */
	private static List<List<String>> flattenLegalMoves(JsonObject tree) {
		var paths = new ArrayList<List<String>>();
		for (var entry : tree.entrySet()) {
			var move = entry.getKey();
			var subtree = entry.getValue().getAsJsonObject();
			if (subtree.entrySet().isEmpty()) {
				paths.add(List.of(move));
			} else {
				for (var continuation : flattenLegalMoves(subtree)) {
					var path = new ArrayList<String>(continuation.size() + 1);
					path.add(move);
					path.addAll(continuation);
					paths.add(path);
				}
			}
		}
		return paths;
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private static Map<String, String> lowercaseKeys(Map<String, String> headers) {
		var result = new HashMap<String, String>();
		headers.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
		return result;
	}

	private static Response error(int status, String message) {
		var body = new JsonObject();
		body.addProperty("error", message);
		return new Response(status, GSON.toJson(body));
	}
}
