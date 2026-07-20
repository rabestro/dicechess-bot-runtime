package lv.id.jc.dicechess.runtime;

import java.util.List;

/**
 * What a strategy is told about the turn it must play — everything the webhook envelope's
 * {@code state} carries that a move-choosing function plausibly needs, beyond the position
 * itself.
 *
 * @param gameId the game's id — lets a strategy keep per-game state (a search tree, a
 *     transposition table) or tag its own logs
 * @param dfen the position plus the rolled dice for the side to move
 * @param remainingMillis milliseconds left on the mover's own clock, or {@code null} for an
 *     untimed game
 * @param opponentRemainingMillis milliseconds left on the opponent's clock, or {@code null} for
 *     an untimed game
 * @param legalMoves every complete legal turn, each as its sequence of UCI micro-moves — the
 *     server's prefix tree, already walked root-to-leaf, so a strategy with no engine of its own
 *     can play by picking one of these directly. An empty list is a genuine auto-pass (the roll
 *     has no legal move); {@code null} means the legal moves are not known — either the envelope
 *     omitted them (past the server's inline cap) and this handler was not given a play-api base
 *     URL to fetch the fallback from, or that fetch failed
 */
public record TurnContext(
		String gameId,
		String dfen,
		Long remainingMillis,
		Long opponentRemainingMillis,
		List<List<String>> legalMoves) {}
