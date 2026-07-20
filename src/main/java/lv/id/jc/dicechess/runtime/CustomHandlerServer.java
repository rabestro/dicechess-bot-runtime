package lv.id.jc.dicechess.runtime;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.Executors;

/**
 * A minimal HTTP server for the Azure Functions custom-handler model: one path, one {@link
 * WebhookHandler}, no framework.
 *
 * <p>Azure starts the custom-handler process and tells it which port to listen on via the
 * {@code FUNCTIONS_CUSTOMHANDLER_PORT} environment variable; {@link #startFromEnvironment}
 * reads it. Nothing here is Azure-specific beyond that one variable — the same server runs
 * identically under {@code func start} locally, or under any other host that can point HTTP
 * traffic at a port.
 */
public final class CustomHandlerServer {

    private static final String DEFAULT_PATH = "/api/webhook";
    private static final int DEFAULT_PORT = 8080;

    private CustomHandlerServer() {
    }

    /**
     * Starts the server on the port named by {@code FUNCTIONS_CUSTOMHANDLER_PORT} (default
     * {@code 8080}), serving {@code handler} at {@code /api/webhook}.
     *
     * @param handler the webhook logic to serve
     * @return the running server; call {@link HttpServer#stop} to shut it down
     * @throws IOException if the port cannot be bound
     */
    public static HttpServer startFromEnvironment(WebhookHandler handler) throws IOException {
        var port = Integer.parseInt(
                System.getenv().getOrDefault("FUNCTIONS_CUSTOMHANDLER_PORT", String.valueOf(DEFAULT_PORT)));
        return start(port, DEFAULT_PATH, handler);
    }

    /**
     * Starts the server on an explicit port and path.
     *
     * @param port    the port to listen on
     * @param path    the path {@code handler} answers on
     * @param handler the webhook logic to serve
     * @return the running server; call {@link HttpServer#stop} to shut it down
     * @throws IOException if the port cannot be bound
     */
    public static HttpServer start(int port, String path, WebhookHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(path, exchange -> {
            try (exchange) {
                var headers = new HashMap<String, String>();
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!values.isEmpty()) {
                        headers.put(name, values.getFirst());
                    }
                });
                var rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var response = handler.handle(headers, rawBody, Instant.now().getEpochSecond());

                var bytes = response.jsonBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }
}
