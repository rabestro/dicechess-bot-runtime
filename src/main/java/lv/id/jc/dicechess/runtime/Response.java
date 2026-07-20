package lv.id.jc.dicechess.runtime;

/**
 * An HTTP response for a webhook delivery: a status code and a JSON body, ready to write to
 * whatever HTTP layer the caller is using.
 *
 * @param status the HTTP status code to return
 * @param jsonBody the response body, already-serialized JSON
 */
public record Response(int status, String jsonBody) {}
