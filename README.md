# DiceChess Bot Runtime

[![CI](https://github.com/rabestro/dicechess-bot-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/rabestro/dicechess-bot-runtime/actions/workflows/ci.yml)
[![Code Quality](https://github.com/rabestro/dicechess-bot-runtime/actions/workflows/qodana.yml/badge.svg)](https://github.com/rabestro/dicechess-bot-runtime/actions/workflows/qodana.yml)
[![Javadoc](https://img.shields.io/badge/Javadoc-jc.id.lv-1E90FF)](https://jc.id.lv/dicechess-bot-runtime/)
[![Release](https://img.shields.io/github/v/tag/rabestro/dicechess-bot-runtime?label=release&sort=semver)](https://github.com/rabestro/dicechess-bot-runtime/packages)
![Java](https://img.shields.io/badge/Java-25-orange)
[![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey)](./LICENSE)

The transport/protocol plumbing shared by DiceChess webhook bots: HMAC-SHA256 signature
verification, the one-time ownership handshake, and (optionally) an HTTP server for the Azure
Functions custom-handler model. A bot author supplies one thing — a function from a
[`TurnContext`](#usage) to a list of UCI moves — and gets a working webhook bot.

Every public type speaks only `String`, `Long`, `java.util.List`, and `java.util.Map`. Nothing
library-specific crosses the boundary, so this is callable identically from Java, Kotlin, or
Scala — see [`dicechess-bot-scala`](https://github.com/rabestro/dicechess-bot-scala) for a real
bot depending on it: engine-linked, so it reads only `ctx.dfen()` and ignores everything else in
`TurnContext`, which is exactly the point — a strategy takes what it needs.

## Layout

| Path | Role |
| --- | --- |
| `Signatures` | HMAC-SHA256 sign/verify, ±5 minute replay window, constant-time comparison. |
| `WebhookHandler` | Orchestrates one delivery: handshake, signature check, dispatch to the strategy function. Never throws. |
| `TurnContext` | What the strategy function sees: `gameId`, `dfen`, the game `clock` (both sides' remaining time plus the per-turn Fischer increment, all in ms — the whole `clock` is `null` for an untimed game), and every complete legal turn already walked out (`null` if unknown). |
| `CustomHandlerServer` | A JDK `HttpServer` wrapper reading `FUNCTIONS_CUSTOMHANDLER_PORT` — optional; bring your own HTTP layer if you'd rather. |
| `JsonFiles` | Generic JSON-object-of-strings file loader (an opening book, or any similar lookup table), degrades gracefully when the file is absent. |

## Usage

```java
Function<TurnContext, List<String>> strategy = ctx -> List.of("e2e4"); // your move logic
String secret = System.getenv("DICECHESS_WEBHOOK_SECRET");
WebhookHandler handler = new WebhookHandler(secret, strategy);
CustomHandlerServer.startFromEnvironment(handler);
```

A strategy with no engine of its own can skip `dfen` entirely and just pick one path from
`ctx.legalMoves()` — pass play-api's base URL to the other `WebhookHandler` constructor and the
rare capped turn (the tree too large to inline) is fetched from the public
`GET /games/{id}/moves` automatically:

```java
WebhookHandler handler = new WebhookHandler(secret, "https://play-api.jc.id.lv", strategy);
```

Full API docs with more examples: <https://jc.id.lv/dicechess-bot-runtime/>.

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
