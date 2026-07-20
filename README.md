# DiceChess Bot Runtime

The transport/protocol plumbing shared by DiceChess webhook bots: HMAC-SHA256 signature
verification, the one-time ownership handshake, and (optionally) an HTTP server for the Azure
Functions custom-handler model. A bot author supplies one thing — a function from a DFEN string
to a list of UCI moves — and gets a working webhook bot.

Every public type speaks only `String`, `java.util.List`, and `java.util.Map`. Nothing
library-specific crosses the boundary, so this is callable identically from Java, Kotlin, or
Scala — see [`dicechess-bot-scala`](https://github.com/rabestro/dicechess-bot-scala) for a real
bot built on the same protocol (currently carrying its own copy of this code; migrating it to
depend on this library instead is a follow-up).

## Licensing

**MIT.** Unlike [`dicechess-bot-scala`](https://github.com/rabestro/dicechess-bot-scala), this
library links no game engine — it never AGPL-copyleft-infects a bot that depends on it.

## Layout

| Path | Role |
| --- | --- |
| `Signatures` | HMAC-SHA256 sign/verify, ±5 minute replay window, constant-time comparison. |
| `WebhookHandler` | Orchestrates one delivery: handshake, signature check, dispatch to the strategy function. Never throws. |
| `CustomHandlerServer` | A JDK `HttpServer` wrapper reading `FUNCTIONS_CUSTOMHANDLER_PORT` — optional; bring your own HTTP layer if you'd rather. |
| `JsonFiles` | Generic JSON-object-of-strings file loader (an opening book, or any similar lookup table), degrades gracefully when the file is absent. |

## Usage

```java
Function<String, List<String>> strategy = dfen -> List.of("e2e4"); // your move logic
String secret = System.getenv("DICECHESS_WEBHOOK_SECRET");
WebhookHandler handler = new WebhookHandler(secret, strategy);
CustomHandlerServer.startFromEnvironment(handler);
```

Full API docs with more examples: <https://rabestro.github.io/dicechess-bot-runtime/>.

### Depend on it

Published to GitHub Packages as `lv.id.jc:dicechess-bot-runtime`.

Maven:

```xml
<dependency>
	<groupId>lv.id.jc</groupId>
	<artifactId>dicechess-bot-runtime</artifactId>
	<version>0.1.0</version>
</dependency>
```

sbt (plain `%`, not `%%` — this is a Java artifact, not cross-built per Scala version):

```scala
libraryDependencies += "lv.id.jc" % "dicechess-bot-runtime" % "0.1.0"
```

## Local development

Requires JDK 25 and Maven.

```bash
mvn test              # SignaturesTest, WebhookHandlerTest, JsonFilesTest, one real-HTTP end-to-end test
mvn javadoc:javadoc    # the doc-quality gate: doclint=all, fails the build on missing/malformed Javadoc
```

## Why no framework

The webhook contract is one HTTP request in, one HTTP response out, on a hard clock — a
dependency-heavy web framework buys nothing here and would work against the "callable from
anywhere" goal. The only third-party dependency is [Gson](https://github.com/google/gson), used
internally to parse/build the small, fixed envelope shapes; it never appears in a public method
signature.
