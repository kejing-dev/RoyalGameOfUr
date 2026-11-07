package io.embodit.royalur.ui.board

import io.embodit.royalur.domain.Ai
import io.embodit.royalur.domain.GameConfig
import io.embodit.royalur.domain.GameFlow
import io.embodit.royalur.domain.GameStore
import io.embodit.royalur.domain.Mode
import io.embodit.royalur.domain.Phase
import io.embodit.royalur.domain.Pos
import io.embodit.royalur.domain.SavedGame
import io.embodit.royalur.domain.Settings
import io.embodit.royalur.domain.Ur
import io.embodit.royalur.ui.board.input.parseLoggedPath
import io.embodit.royalur.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * build.md「命中目标 → 格子必须有回放单测」+ template README 必带单测: replay what the system really reports as the hit
 * node — a square's rim marker, its holder, any GLB mesh under a piece / tower / cup / tray holder — through the same
 * name resolver the board-input template calls (TargetResolver.byName + resolvePath), then on into GameFlow through the
 * same [routeFlow] the volume uses. Every hit on a square, piece or tower must resolve to that square / piece / tower.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private val board = listOf("boardRoot", "tiltRoot", "sceneRoot", "root")
    private val scene = listOf("sceneRoot", "root")

    private fun sq(path: List<String>) = BoardTargets.squareOf(BoardTargets.resolvePath(path))

    @Test fun everySquareMarkerAndHolderResolvesToItsSquare() {
        for (s in Ur.SQUARES) {
            val holder = "sq:${s.name}"
            assertEquals(s.name, sq(listOf(holder) + board))                         // the holder's click area (rim)
            for (i in 0..3) assertEquals(s.name, sq(listOf("mk:$holder:$i", holder) + board)) // the visible rim marker
        }
    }

    @Test fun everyPieceMeshResolvesToTheSquareItStandsOn() {
        for (side in 0..1) for (p in 1..14) {
            val want = Ur.square(side, p).name
            assertEquals(want, sq(listOf("pc:$side:$p") + scene))                                  // the clone root (holder)
            assertEquals(want, sq(listOf("Mesh_0", "PieceNear", "pc:$side:$p") + scene))           // a GLB mesh node under it
        }
    }

    @Test fun towersCupAndTrayResolveFromTheirMeshes() {
        for (side in 0..1) for (end in listOf(0, 15)) {
            val holder = "tw:$side:$end"
            assertEquals(holder, sq(listOf("Mesh_0", "towerModel", holder) + if (side == 0) scene else board))
            // a piece standing in a tower is named after the tower, so it taps the tower
            assertEquals(holder, sq(listOf("Mesh_0", "PieceFar", holder) + scene))
        }
        assertEquals("cup", sq(listOf("Mesh_0", "cupModel", "cup", "cupPivot") + scene))
        assertEquals("tray", sq(listOf("Mesh_0", "trayModel", "tray") + scene))
    }

    @Test fun nonTargetsResolveToNothing() {
        for (path in listOf(
            listOf("boardModel") + board, listOf("Mesh_0", "boardModel") + board,       // the board body takes no hits
            listOf("pc:0:x") + scene, listOf("fly") + scene,                              // unplaced / flying piece
            listOf("litRing:0:halo", "litRing:0") + scene, listOf("rosetteGlow") + scene, listOf("keyLight", "root"),
            listOf("pc:0:99") + scene, listOf("sq:Z9") + board,
        )) assertNull(path.toString(), BoardTargets.resolvePath(path))
    }

    /** Real `path=` values from the v0.1.3 headset logcat (2026-11-06 23:35-23:42, 56 taps, hand-ray + eye-hand). */
    @Test fun replayHeadsetLogPaths() {
        val logged = listOf(
            "cup", "pc:0:3", "pc:0:2", "pc:0:14", "pc:0:10", "tw:0:15", "tw:0:0", "sq:C1", "sq:B8", "sq:B6", "pc:0:7",
            "sq:C7", "sq:C3", "sq:B7", "sq:B5", "pc:1:7", "pc:1:5", "pc:0:5", "pc:0:12", "pc:0:11",
        )
        for (p in logged) assertNotEquals(p, "-", BoardTargets.squareOf(resolvePath(parseLoggedPath(p), BoardTargets::targetOf)))
        // the v0.1.4 holders add marker / mesh children below those names: still the same target
        assertEquals("C1", BoardTargets.squareOf(resolvePath(parseLoggedPath("mk:sq:C1:2 < sq:C1 < boardRoot < tiltRoot < sceneRoot < root"), BoardTargets::targetOf)))
        assertEquals(Ur.square(0, 3).name, BoardTargets.squareOf(resolvePath(parseLoggedPath("Mesh_0 < pc:0:3 < sceneRoot < root"), BoardTargets::targetOf)))
    }

    private class Mem : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    private fun flow(scope: TestScope) = GameFlow(
        scope = scope.backgroundScope, engineDispatcher = StandardTestDispatcher(scope.testScheduler),
        clock = { scope.testScheduler.currentTime }, store = Mem(),
        settings = { Settings(firstRunDone = true, coachDone = true) }, onSettings = {}, onSound = {},
    )

    private fun tap(f: GameFlow, path: List<String>) = routeFlow(f, BoardTargets.resolvePath(path))

    @Test fun replayedHitsRollPickMoveAndBearOff() = runTest {
        val f = flow(this)
        f.newGame(GameConfig(Mode.TWO_PLAYER, Ai.EASY, Ur.NEAR)); runCurrent()
        f.setup(Pos(listOf(5, 14, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0, 0)), Ur.NEAR); runCurrent()
        // roll by a hit on the cup's GLB mesh
        f.forcedRoll = 2
        tap(f, listOf("Mesh_0", "cupModel", "cup", "cupPivot") + scene); runCurrent()
        advanceTimeBy(GameFlow.ROLL_MS + 50); runCurrent()
        assertEquals(Phase.PICK, f.state.value.phase)
        // pick the piece on progress 5 through its mesh, then the target square through its rim marker
        tap(f, listOf("Mesh_0", "PieceNear", "pc:0:5") + scene)
        assertEquals(Phase.PICKED, f.state.value.phase)
        val to = Ur.square(Ur.NEAR, 7).name
        tap(f, listOf("mk:sq:$to:1", "sq:$to") + board)
        advanceTimeBy(2 * GameFlow.STEP_MS + 50); runCurrent()
        assertEquals(listOf(14, 7), f.state.value.pos.near.take(2).sortedDescending())
        // the other side passes (roll 0), then bear off progress 14 with a 1 by tapping the finish tower's wood
        f.forcedRoll = 0; tap(f, listOf("cup", "cupPivot") + scene); advanceTimeBy(GameFlow.ROLL_MS + GameFlow.NO_MOVE_MS + 100); runCurrent()
        assertEquals(Ur.NEAR, f.state.value.side)
        f.forcedRoll = 1; tap(f, listOf("Mesh_0", "cupModel", "cup", "cupPivot") + scene); advanceTimeBy(GameFlow.ROLL_MS + 50); runCurrent()
        tap(f, listOf("Mesh_0", "PieceNear", "pc:0:14") + scene)
        tap(f, listOf("Mesh_0", "towerModel", "tw:0:15") + scene)
        advanceTimeBy(GameFlow.STEP_MS + GameFlow.OFF_MS + 50); runCurrent()
        assertEquals(1, f.state.value.pos.off(Ur.NEAR))
    }
}
