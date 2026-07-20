/**
 * Transport runtime for DiceChess webhook bots.
 *
 * <p>A DiceChess bot is a small HTTP endpoint: the platform ({@code play-api}) delivers each
 * turn as a signed HTTP request, and the bot answers with the moves it chooses. Everything in
 * this package is the plumbing around that contract — HMAC-SHA256 signature verification
 * ({@link lv.id.jc.dicechess.runtime.Signatures}), the one-time ownership handshake and delivery
 * orchestration ({@link lv.id.jc.dicechess.runtime.WebhookHandler}), and (optionally) the HTTP
 * server itself ({@link lv.id.jc.dicechess.runtime.CustomHandlerServer}) — so a bot author
 * supplies nothing but a move-choosing function.
 *
 * <p>The whole wiring for a bot's {@code main} method:
 *
 * {@snippet lang="java" :
 * Function<TurnContext, List<String>> strategy = ctx -> List.of("e2e4"); // your move logic
 * String secret = System.getenv("DICECHESS_WEBHOOK_SECRET");
 * WebhookHandler handler = new WebhookHandler(secret, strategy);
 * CustomHandlerServer.startFromEnvironment(handler);
 * }
 *
 * <p>Every public type here speaks only {@code String}, {@code Long}, {@code java.util.List},
 * and {@code java.util.Map} — no Gson type, and nothing library-specific, crosses the public
 * boundary. A bot written in Kotlin or Scala calls this exactly like a Java bot would; the
 * strategy itself is a plain {@code java.util.function.Function}, so a lambda, a method
 * reference, or a Scala function converted via {@code asJava} all work unchanged.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Game logic, DFEN parsing, and move legality are the bot author's concern (or the real
 * engine's, if the bot links one) — this package only delivers a {@link
 * lv.id.jc.dicechess.runtime.TurnContext} to the strategy function and relays back whatever
 * moves it returns. The inline legal-move tree the envelope sometimes carries is deliberately
 * not surfaced yet — reading it (and falling back to a REST fetch when the envelope omits it
 * past the server's cap) would need this library to make its own outbound calls, which it does
 * not do today. It also does not read or write an opening book itself; {@link
 * lv.id.jc.dicechess.runtime.JsonFiles} is a generic string-map loader a strategy can use for
 * that, or for any similarly simple lookup table.
 */
package lv.id.jc.dicechess.runtime;
