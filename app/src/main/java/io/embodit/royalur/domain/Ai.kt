package io.embodit.royalur.domain

import kotlin.random.Random

/**
 * The two AI levels (design gameSpec.ai, dice-game heuristics, no search):
 *  - EASY  = a random legal move (seeded, reproducible; a beginner can beat it, B27)
 *  - SMART = priority table, ties broken at random:
 *      bear off > capture (the further the captured piece had come, the better) > take the centre rosette B4 >
 *      land on another rosette (roll again) > escape (a shared-lane piece with a rival 1-4 squares behind it moves
 *      to safety) > enter a new piece (fewer than 3 on the board) > progress (the most advanced piece first)
 * The hint ([提示]) shows what SMART would play.
 */
object Ai {
    const val EASY = 1
    const val SMART = 2

    fun choose(pos: Pos, moves: List<Move>, level: Int, random: Random): Move? {
        if (moves.isEmpty()) return null
        if (level == EASY) return moves[random.nextInt(moves.size)]
        val scored = moves.map { it to score(pos, it) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        return top[random.nextInt(top.size)]
    }

    /** Tier * 100 + a within-tier key (higher is better). */
    fun score(pos: Pos, m: Move): Int {
        val tier = tier(pos, m)
        val key = when (tier) {
            7 -> m.to // capture: the further the rival had come
            1 -> m.from // progress: the most advanced piece
            else -> 0
        }
        return tier * 100 + key
    }

    /** 8 bear off, 7 capture, 6 centre rosette, 5 other rosette, 4 escape, 3 enter, 1 progress (0 = walk into danger). */
    fun tier(pos: Pos, m: Move): Int {
        if (m.off) return 8
        if (m.captures) return 7
        if (m.to == Ur.CENTRE_ROSETTE) return 6
        if (m.rosette) return 5
        val after = Ur.apply(pos, m)
        if (threatened(pos, m.side, m.from) && !threatened(after, m.side, m.to)) return 4
        if (m.from == Ur.START && pos.onBoard(m.side) < 3) return 3
        if (threatened(after, m.side, m.to)) return 0
        return 1
    }

    /** A piece of [side] at progress [p] can be captured next turn: it sits in the shared lane (not B4) with a rival 1-4 behind. */
    fun threatened(pos: Pos, side: Int, p: Int): Boolean {
        if (p !in Ur.SHARED || p == Ur.CENTRE_ROSETTE) return false
        return pos.of(Ur.other(side)).any { q -> q in (p - 4)..(p - 1) && q < Ur.OFF }
    }
}
