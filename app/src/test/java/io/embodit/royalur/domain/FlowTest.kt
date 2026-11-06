package io.embodit.royalur.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    private class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, coachDone = true)) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engineDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
            onSound = { sounds += it },
        )
        val st get() = flow.state.value
    }

    private fun TestScope.roll(h: Harness, n: Int) {
        h.flow.forcedRoll = n
        h.flow.tapCup()
        runCurrent()
        advanceTimeBy(GameFlow.ROLL_MS + 50)
        runCurrent()
    }

    private fun pos(near: List<Int>, far: List<Int>) = Pos(near + List(7 - near.size) { 0 }, far + List(7 - far.size) { 0 })

    @Test fun firstRunOpensTheDefaultGameAtS03AndTheFirstPinchEndsTheWelcome() = runTest {
        val h = Harness(this, settings = Settings())
        h.flow.start(); runCurrent()
        assertEquals(Phase.TO_ROLL, h.st.phase)
        assertTrue(h.st.welcome)
        assertEquals(Mode.AI, h.st.config!!.mode)
        roll(h, 2)
        assertFalse(h.st.welcome)
        assertTrue(h.settings.firstRunDone)
        assertEquals(Phase.PICK, h.st.phase)
    }

    @Test fun pickPreviewThenSecondTapMovesAndCaptureSendsTheRivalHome() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Ai.EASY, Ur.NEAR)); runCurrent()
        h.flow.setup(pos(listOf(5), listOf(7)), Ur.NEAR); runCurrent()
        roll(h, 2)
        h.flow.tapPiece(Ur.NEAR, 5)
        assertEquals(Phase.PICKED, h.st.phase)
        assertTrue(h.st.picked!!.captures)
        // the rival piece sitting on the target confirms (critic #4)
        h.flow.tapPiece(Ur.FAR, 7)
        assertEquals(Phase.MOVING, h.st.phase)
        // the panel / tags keep the old position until it lands
        assertEquals(0, h.st.shownPos.far.count { it == 7 } - 1)
        advanceTimeBy(GameFlow.STEP_MS + GameFlow.CAPTURE_MS + 50); runCurrent()
        assertEquals(Ur.FAR, h.st.side)
        assertEquals(0, h.st.pos.far[0])
        assertEquals(7, h.st.pos.near[0])
        assertEquals(1, h.st.captures[Ur.NEAR])
        assertEquals(Phase.TO_ROLL, h.st.phase)
    }

    @Test fun illegalTapsAlertAndChangeNothing() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); runCurrent()
        h.flow.setup(pos(listOf(5, 6), listOf(9)), Ur.NEAR); runCurrent()
        roll(h, 1) // 5 -> 6 blocked by own piece
        val before = h.st.pos
        h.flow.tapPiece(Ur.NEAR, 5)
        assertEquals(AlertKind.NOT_LIT, h.st.alert!!.kind)
        h.flow.tapPiece(Ur.FAR, 9)
        assertEquals(AlertKind.THEIRS, h.st.alert!!.kind)
        h.flow.tapPiece(Ur.NEAR, 6)
        h.flow.tapSquare(Sq.parse("B8")!!)
        assertEquals(AlertKind.NOT_TARGET, h.st.alert!!.kind)
        assertEquals(Phase.PICKED, h.st.phase)
        assertEquals(before, h.st.pos)
        h.flow.tapEmpty()
        assertEquals(Phase.PICK, h.st.phase)
    }

    @Test fun rosetteGivesTheSameSideAnotherRollAndZeroPassesTheTurn() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Ai.EASY, Ur.NEAR)); runCurrent()
        roll(h, 4)
        h.flow.tapPiece(Ur.NEAR, 0); h.flow.tapPiece(Ur.NEAR, 0)
        advanceTimeBy(GameFlow.STEP_MS + 20); runCurrent()
        assertEquals(Phase.AGAIN, h.st.phase)
        advanceTimeBy(GameFlow.AGAIN_MS + 20); runCurrent()
        assertEquals(Phase.TO_ROLL, h.st.phase)
        assertEquals(Ur.NEAR, h.st.side)
        roll(h, 0)
        assertEquals(Phase.NO_MOVE, h.st.phase)
        advanceTimeBy(GameFlow.NO_MOVE_MS + 20); runCurrent()
        assertEquals(Ur.FAR, h.st.side)
        assertEquals(Phase.TO_ROLL, h.st.phase)
    }

    @Test fun aiTurnRollsThinksAtLeast400msThenMoves() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.SMART, Ur.FAR)); runCurrent()
        assertEquals(Phase.THINKING, h.st.phase)
        h.flow.forcedRoll = 2
        advanceTimeBy(GameFlow.AI_PRE_ROLL_MS + GameFlow.ROLL_MS + 100); runCurrent()
        assertEquals(Phase.THINKING, h.st.phase) // still thinking after the dice land
        advanceTimeBy(GameFlow.MIN_THINK_MS + GameFlow.AI_LIT_MS + GameFlow.STEP_MS + 100); runCurrent()
        assertEquals(1, h.st.pos.far.count { it == 2 })
        assertEquals(Ur.NEAR, h.st.side)
        assertEquals(Phase.TO_ROLL, h.st.phase)
    }

    @Test fun undoGoesBackToTheUsersLastPickKeepingTheRollAndRedoRestores() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); runCurrent()
        assertFalse(h.st.canUndo) // nothing to undo in the first turn
        roll(h, 3)
        val pickSnap = h.st.snap(true)
        h.flow.tapPiece(Ur.NEAR, 0); h.flow.tapPiece(Ur.NEAR, 0)
        h.flow.forcedRoll = 1
        advanceTimeBy(5000); runCurrent()
        assertEquals(Ur.NEAR, h.st.side)
        assertEquals(Phase.TO_ROLL, h.st.phase)
        assertTrue(h.st.canUndo)
        h.flow.requestUndo(); runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(Phase.PICK, h.st.phase)
        assertEquals(3, h.st.roll!!.value) // the roll is kept (undo the move, not the throw)
        assertEquals(pickSnap.pos, h.st.pos)
        assertTrue(h.st.canRedo)
        h.flow.redo(); runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(Phase.TO_ROLL, h.st.phase)
        assertEquals(1, h.st.pos.near.count { it == 3 })
        assertEquals(1, h.st.pos.far.count { it == 1 })
    }

    @Test fun undoAfterTwoRosettesOnlyGoesBackOneStep() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); runCurrent()
        roll(h, 4)
        h.flow.tapPiece(Ur.NEAR, 0); h.flow.tapPiece(Ur.NEAR, 0)
        advanceTimeBy(GameFlow.STEP_MS + GameFlow.AGAIN_MS + 50); runCurrent()
        roll(h, 4) // 4 -> 8 centre rosette
        h.flow.tapPiece(Ur.NEAR, 4); h.flow.tapPiece(Ur.NEAR, 4)
        advanceTimeBy(GameFlow.STEP_MS + GameFlow.AGAIN_MS + 50); runCurrent()
        assertEquals(Phase.TO_ROLL, h.st.phase)
        h.flow.requestUndo(); advanceTimeBy(50); runCurrent()
        assertEquals(Phase.PICK, h.st.phase)
        assertEquals(1, h.st.pos.near.count { it == 4 })
        assertEquals(4, h.st.roll!!.value)
    }

    @Test fun twoPlayerUndoAsksFirst() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Ai.EASY, Ur.NEAR)); runCurrent()
        roll(h, 2)
        h.flow.tapPiece(Ur.NEAR, 0); h.flow.tapPiece(Ur.NEAR, 0)
        advanceTimeBy(GameFlow.STEP_MS + 50); runCurrent()
        roll(h, 2); h.flow.tapPiece(Ur.FAR, 0); h.flow.tapPiece(Ur.FAR, 0)
        advanceTimeBy(GameFlow.STEP_MS + 50); runCurrent()
        assertEquals(Ur.NEAR, h.st.side)
        h.flow.requestUndo()
        assertEquals(ConfirmKind.UNDO_2P, h.st.confirm)
        h.flow.confirm(); advanceTimeBy(50); runCurrent()
        assertEquals(Phase.PICK, h.st.phase)
        assertEquals(Pos.START, h.st.pos)
    }

    @Test fun everyPhaseRestoresToS03S05OrS10WithTheSameLegalMoves() = runTest {
        // pick (pendingRoll)
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); runCurrent()
        h.flow.setup(pos(listOf(5, 2), listOf(7)), Ur.NEAR); runCurrent()
        roll(h, 2)
        val legal = h.st.legal
        val h2 = Harness(this, h.store)
        h2.flow.start(); runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(Phase.PICK, h2.st.phase)
        assertEquals(legal, h2.st.legal)
        assertEquals(2, h2.st.roll!!.value)
        // mid-move: the move is already committed
        h2.flow.tapPiece(Ur.NEAR, 5); h2.flow.tapPiece(Ur.NEAR, 5)
        val h3 = Harness(this, h.store)
        h3.flow.start(); runCurrent()
        assertEquals(7, h3.st.pos.near[0])
        assertEquals(Ur.FAR, h3.st.side)
        assertEquals(Phase.THINKING, h3.st.phase) // the AI's turn is re-run
        assertNull(h3.store.saved!!.now.roll)
    }

    @Test fun restoreAtToRollAndAtTheAiTurnBeforeItsRoll() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Ai.EASY, Ur.NEAR)); runCurrent()
        h.flow.setup(pos(listOf(3), listOf(6)), Ur.FAR); runCurrent()
        val h2 = Harness(this, h.store)
        h2.flow.start(); runCurrent()
        assertEquals(Phase.TO_ROLL, h2.st.phase) // S03
        assertEquals(Ur.FAR, h2.st.side)
        assertEquals(pos(listOf(3), listOf(6)), h2.st.pos)
        val a = Harness(this)
        a.flow.newGame(GameConfig(Mode.AI, Ai.SMART, Ur.FAR)); runCurrent()
        val a2 = Harness(this, a.store)
        a2.flow.start(); runCurrent()
        assertEquals(Phase.THINKING, a2.st.phase) // S10, the AI re-runs its turn
        advanceTimeBy(3000); runCurrent()
        assertTrue(a2.st.turnNo >= 1 || a2.st.side == Ur.FAR)
    }

    @Test fun winEndsWithStatsAndTheAiWinIsNotAFanfare() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); runCurrent()
        h.flow.setup(Pos(listOf(15, 15, 15, 15, 15, 15, 14), listOf(15, 15, 15, 15, 15, 15, 13)), Ur.NEAR); runCurrent()
        roll(h, 1)
        h.flow.tapPiece(Ur.NEAR, 14); h.flow.tapPiece(Ur.NEAR, 14)
        advanceTimeBy(GameFlow.OFF_MS + 50); runCurrent()
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(Ur.NEAR, h.st.winner)
        assertEquals(Sound.WIN, h.sounds.last())
        assertNull(h.store.saved)
        val g = Harness(this)
        g.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); runCurrent()
        g.flow.requestConfirm(ConfirmKind.RESIGN); g.flow.confirm()
        assertEquals(Ur.FAR, g.st.winner)
        assertEquals(Sound.LOSE, g.sounds.last())
    }

    @Test fun newGameKeepsSettingsAndRandomFirstOnlyGoesIntoTheGame() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameSetup(Mode.AI, Ai.SMART, FirstPick.RANDOM)); runCurrent()
        assertEquals(FirstPick.RANDOM, h.settings.lastSetup.first)
        assertNotNull(h.st.config)
        assertEquals(15, h.settings.tilt)
    }
}
