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
 * <h2>Bots with no engine of their own</h2>
 *
 * <p>{@link lv.id.jc.dicechess.runtime.TurnContext#legalMoves} carries every complete legal
 * turn, already walked from the server's prefix tree — a strategy can pick straight from that
 * list and never parse a DFEN or generate a move itself. The rare turn where the tree is too
 * large to inline (the envelope carries {@code null}) falls back to {@code GET
 * /games/{id}/moves} — a public, unauthenticated endpoint — if the {@link
 * lv.id.jc.dicechess.runtime.WebhookHandler} constructor that takes play-api's base URL was
 * used; otherwise {@code legalMoves} is simply {@code null} on that turn, same as it always is
 * when a strategy doesn't need it.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>DFEN parsing and independent move legality are still not this package's concern — it
 * relays the server's own tree rather than recomputing one, so an engine-linked bot (like
 * {@code dicechess-bot-scala}) is free to ignore {@code legalMoves} entirely and keep deriving
 * moves from {@code dfen} itself. It also does not read or write an opening book itself; {@link
 * lv.id.jc.dicechess.runtime.JsonFiles} is a generic string-map loader a strategy can use for
 * that, or for any similarly simple lookup table.
 */
package lv.id.jc.dicechess.runtime;
