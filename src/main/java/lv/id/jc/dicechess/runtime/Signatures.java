package lv.id.jc.dicechess.runtime;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing and verification for the DiceChess webhook delivery protocol.
 *
 * <p>The signed payload is {@code timestamp + "." + body}, hex-encoded. A delivery is accepted
 * only if its timestamp falls within {@link #REPLAY_WINDOW_SECONDS} of the verifier's clock,
 * and the comparison is constant-time to avoid leaking the secret through timing.
 */
public final class Signatures {

	/** The signature is only accepted within this many seconds of the current time, either side. */
	public static final long REPLAY_WINDOW_SECONDS = 300;

	private static final String ALGORITHM = "HmacSHA256";

	private Signatures() {}

	/**
	 * Computes the hex-encoded HMAC-SHA256 signature for a delivery.
	 *
	 * @param secret the webhook secret issued by the platform
	 * @param timestampEpochSeconds the delivery timestamp, Unix epoch seconds
	 * @param body the raw request body, exactly as transmitted
	 * @return the lowercase hex signature
	 */
	public static String sign(String secret, long timestampEpochSeconds, String body) {
		try {
			var mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			var payload = timestampEpochSeconds + "." + body;
			var raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(raw);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HmacSHA256 must be available on every JDK", e);
		}
	}

	/**
	 * Verifies a delivery's signature and freshness.
	 *
	 * @param secret the webhook secret issued by the platform
	 * @param timestampEpochSeconds the delivery's claimed timestamp, Unix epoch seconds
	 * @param body the raw request body, exactly as received
	 * @param signature the hex signature supplied with the delivery
	 * @param nowEpochSeconds the verifier's current time, Unix epoch seconds
	 * @return {@code true} if the timestamp is within {@link #REPLAY_WINDOW_SECONDS} and the
	 *     signature matches
	 */
	public static boolean verify(
			String secret, long timestampEpochSeconds, String body, String signature, long nowEpochSeconds) {
		if (Math.abs(nowEpochSeconds - timestampEpochSeconds) > REPLAY_WINDOW_SECONDS) {
			return false;
		}
		var expected = sign(secret, timestampEpochSeconds, body);
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
	}
}
