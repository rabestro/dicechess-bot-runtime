package lv.id.jc.dicechess.runtime;

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
 */
public record TurnContext(String gameId, String dfen, Long remainingMillis, Long opponentRemainingMillis) {}
