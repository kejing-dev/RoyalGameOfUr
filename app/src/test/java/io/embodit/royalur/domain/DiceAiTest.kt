package io.embodit.royalur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DiceAiTest {
    @Test fun rollDistributionIsBinomialWithinThreePercent() {
        val rng = Random(7)
        val counts = IntArray(5)
        repeat(16000) { counts[Roll.random(rng).value]++ }
        val expect = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0).map { it / 16 }
        for (v in 0..4) assertTrue("value $v ${counts[v]}", abs(counts[v] / 16000.0 - expect[v]) < 0.03)
    }

    @Test fun everyThrowSettlesFlatWithTheCommittedTipUpAndNeverFlipsAtTheEnd() {
        var longest = 0.0
        for (seed in 1..60) for (white in listOf(true, false)) {
            val t = Tetra.createThrow(white, seed * 7919)
            val (top, align) = Tetra.topVertex(t.rest.quaternion)
            assertEquals("seed $seed", white, Tetra.isWhite(top))
            assertTrue(align > 0.999)
            // resting height = the centroid over a face, within 2 mm
            assertTrue("seed $seed y=${t.rest.position[1]}", abs(t.rest.position[1] - Tetra.REST_HEIGHT_M) < 0.002)
            // the last 40 ms keep the same top vertex (no flip on the last frame)
            val n = t.frames.size
            for (k in maxOf(0, n - 10) until n) assertEquals(top, Tetra.topVertex(t.frames[k].quaternion).first)
            // stays inside its 5 x 5 cm lane
            for (f in t.frames) { assertTrue(abs(f.position[0]) < 0.026); assertTrue(abs(f.position[2]) < 0.026) }
            longest = maxOf(longest, t.duration)
        }
        println("longest throw ${"%.2f".format(longest)} s")
        assertTrue(longest < 4.0)
    }

    @Test fun forcedRollShowsExactlyN() {
        val rng = Random(1)
        for (n in 0..4) assertEquals(n, Roll.forced(n, rng).value)
    }

    @Test fun smartPicksByPriorityInConstructedPositions() {
        val r = Random(3)
        fun best(near: List<Int>, far: List<Int>, roll: Int): Move {
            val p = Pos(near + List(7 - near.size) { 0 }, far + List(7 - far.size) { 0 })
            return Ai.choose(p, Ur.legalMoves(p, Ur.NEAR, roll), Ai.SMART, r)!!
        }
        // 1 bear off beats capture
        assertTrue(best(listOf(13, 3), listOf(5), 2).off)
        // 2 capture beats rosette; the further rival first
        assertEquals(10, best(listOf(3, 8), listOf(5, 10), 2).to)
        assertTrue(best(listOf(5, 2), listOf(7), 2).captures)
        // 3 centre rosette beats another rosette
        assertEquals(8, best(listOf(6, 2), emptyList(), 2).to)
        // 4 other rosette (roll again)
        assertTrue(best(listOf(12, 1), emptyList(), 2).rosette)
        // 5 escape: the piece at 9 has a rival at 7 behind it
        assertEquals(9, best(listOf(9, 2), listOf(7), 3).from)
        // 6 enter when fewer than 3 on the board
        assertEquals(0, best(listOf(6, 15, 15, 15, 15, 15, 0), emptyList(), 1).from)
        // 7 progress: the most advanced piece moves
        assertEquals(10, best(listOf(10, 6, 2, 15, 15, 15, 15), emptyList(), 1).let { it.from })
    }

    @Test fun smartBeatsEasyAtLeastSeventyPercentOver200Games() {
        var smartWins = 0
        val rng = Random(2026)
        repeat(200) { g ->
            var pos = Pos.START
            var side = g % 2
            val smartSide = (g / 2) % 2
            var guard = 0
            while (Ur.winner(pos) == null && guard++ < 5000) {
                val roll = Roll.random(rng).value
                val moves = Ur.legalMoves(pos, side, roll)
                val m = Ai.choose(pos, moves, if (side == smartSide) Ai.SMART else Ai.EASY, rng)
                if (m == null) { side = 1 - side; continue }
                pos = Ur.apply(pos, m)
                if (!m.rosette) side = 1 - side
            }
            if (Ur.winner(pos) == smartSide) smartWins++
        }
        println("smart won $smartWins / 200")
        assertTrue("smart won $smartWins", smartWins >= 140)
    }
}
