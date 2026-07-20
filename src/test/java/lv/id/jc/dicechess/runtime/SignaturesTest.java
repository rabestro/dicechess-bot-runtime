package lv.id.jc.dicechess.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The signature vector here is the ecosystem-wide one: the same bytes are asserted in play-api's
 * WebhookSecuritySuite, the TypeScript/Python starters, and dicechess-bot-scala's WebhookSuite —
 * every implementation provably speaks one scheme.
 */
class SignaturesTest {

	private static final String SECRET = "test-webhook-secret";
	private static final long NOW = 1752750000L;

	@Test
	void signMatchesTheEcosystemWideVector() {
		assertThat(Signatures.sign(SECRET, NOW, "{\"hello\":true}"))
				.isEqualTo("5f4fbf105bab278dc6205788389e09884bd554b1f866ca11ccc9ce97ddd9b3f6");
	}

	@Test
	void verifyAcceptsAGenuineFreshSignature() {
		var body = "{\"type\":\"yourTurn\"}";
		var signature = Signatures.sign(SECRET, NOW, body);
		assertThat(Signatures.verify(SECRET, NOW, body, signature, NOW)).isTrue();
	}

	@Test
	void verifyRejectsAWrongSecret() {
		var body = "{\"type\":\"yourTurn\"}";
		var signature = Signatures.sign("some-other-secret", NOW, body);
		assertThat(Signatures.verify(SECRET, NOW, body, signature, NOW)).isFalse();
	}

	@Test
	void verifyRejectsATamperedSignature() {
		var body = "{\"type\":\"yourTurn\"}";
		assertThat(Signatures.verify(SECRET, NOW, body, "deadbeef", NOW)).isFalse();
	}

	@Test
	void verifyRejectsAStaleTimestampEvenWithAGenuineSignature() {
		var body = "{\"type\":\"yourTurn\"}";
		var staleTimestamp = NOW - 3600;
		var signature = Signatures.sign(SECRET, staleTimestamp, body);
		assertThat(Signatures.verify(SECRET, staleTimestamp, body, signature, NOW)).isFalse();
	}

	@Test
	void verifyAcceptsExactlyTheEdgeOfTheReplayWindow() {
		var body = "{\"type\":\"yourTurn\"}";
		var timestamp = NOW - Signatures.REPLAY_WINDOW_SECONDS;
		var signature = Signatures.sign(SECRET, timestamp, body);
		assertThat(Signatures.verify(SECRET, timestamp, body, signature, NOW)).isTrue();
	}
}
