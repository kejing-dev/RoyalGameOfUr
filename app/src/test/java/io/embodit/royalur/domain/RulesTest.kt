package io.embodit.royalur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun pos(near: List<Int>, far: List<Int>) = Pos(near + List(7 - near.size) { 0 }, far + List(7 - far.size) { 0 })

    @Test fun routeMapsFourteenSquaresPerSideAndSharesTheMiddleLane() {
        for (side in 0..1) {
            val sqs = (1..14).map { Ur.square(side, it) }
            assertEquals(14, sqs.toSet().size)
            assertEquals(Sq(Ur.ownRow(side), 4), sqs[0])
            assertEquals(Sq(Ur.ownRow(side), 1), sqs[3])
            assertEquals(Sq(Ur.ownRow(side), 8), sqs[12])
            assertEquals(Sq(Ur.ownRow(side), 7), sqs[13])
        }
        for (p in 5..12) assertEquals(Ur.square(0, p), Ur.square(1, p))
        for (p in 1..4) assertFalse(Ur.square(0, p) == Ur.square(1, p))
        assertEquals(20, Ur.SQUARES.size)
        val rosettes = Ur.SQUARES.filter { Ur.isRosette(it) }.map { it.name }.toSet()
        assertEquals(setOf("A1", "C1", "B4", "A7", "C7"), rosettes)
        for (side in 0..1) for (p in 1..14) assertEquals(p in Ur.ROSETTES, Ur.isRosette(Ur.square(side, p)))
    }

    @Test fun captureOnlyInTheSharedLaneButNotOnTheCentreRosette() {
        for (t in listOf(5, 6, 7, 9, 10, 11, 12)) {
            val p = pos(listOf(t - 2), listOf(t))
            val m = Ur.legalMoves(p, Ur.NEAR, 2).first { it.from == t - 2 }
            assertTrue("capture at $t", m.captures)
            val after = Ur.apply(p, m)
            assertEquals(0, after.far[0])
            assertEquals(t, after.near[0])
        }
        // B4 (progress 8) is safe: the piece cannot land there at all
        val safe = pos(listOf(6), listOf(8))
        assertTrue(Ur.legalMoves(safe, Ur.NEAR, 2).none { it.from == 6 })
        // private squares never collide with the rival
        val priv = pos(listOf(1), listOf(3))
        assertFalse(Ur.legalMoves(priv, Ur.NEAR, 2).first { it.from == 1 }.captures)
    }

    @Test fun ownPieceBlocksExactBearOffAndOvershootIsIllegal() {
        val p = pos(listOf(3, 5), emptyList())
        assertTrue(Ur.legalMoves(p, Ur.NEAR, 2).none { it.from == 3 })
        val q = pos(listOf(13, 14, 12), emptyList())
        val m2 = Ur.legalMoves(q, Ur.NEAR, 2)
        assertTrue(m2.first { it.from == 13 }.off)
        assertTrue(m2.none { it.from == 14 }) // 16 > 15
        assertTrue(Ur.legalMoves(q, Ur.NEAR, 1).first { it.from == 14 }.off)
        assertTrue(Ur.legalMoves(q, Ur.NEAR, 3).first { it.from == 12 }.off)
    }

    @Test fun zeroAndBlockedRollsHaveNoMoves() {
        assertTrue(Ur.legalMoves(Pos.START, Ur.NEAR, 0).isEmpty())
        // every piece borne off but one that is blocked by overshoot
        val p = Pos(listOf(15, 15, 15, 15, 15, 15, 14), List(7) { 0 })
        assertTrue(Ur.legalMoves(p, Ur.NEAR, 3).isEmpty())
        assertEquals(1, Ur.legalMoves(Pos.START, Ur.NEAR, 4).size) // all in the tower = one choice
    }

    @Test fun rosettesAreFlaggedAndWinnerNeedsAllSeven() {
        val m = Ur.legalMoves(Pos.START, Ur.NEAR, 4).single()
        assertTrue(m.rosette)
        assertEquals(6, m.piece) // the top of the start tower
        assertNull(Ur.winner(Pos(List(6) { 15 } + 14, List(7) { 0 })))
        assertEquals(Ur.NEAR, Ur.winner(Pos(List(7) { 15 }, List(7) { 0 })))
        assertEquals(Ur.FAR, Ur.winner(Pos(List(7) { 0 }, List(7) { 15 })))
    }

    @Test fun saveCodecRoundTrips() {
        val snap = Snap(Pos(listOf(0, 3, 15, 8, 0, 0, 12), listOf(5, 0, 0, 0, 14, 15, 15)), Ur.FAR, Roll(listOf(true, false, true, true), listOf(1, -2, 3, 4)), 17, listOf(2, 1), listOf(3, 4), Move(0, 1, 1, 3))
        val g = SavedGame(GameConfig(Mode.TWO_PLAYER, Ai.SMART, Ur.FAR), snap, listOf(snap, snap.copy(roll = null)), 12345, true)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("garbage"))
    }
}
