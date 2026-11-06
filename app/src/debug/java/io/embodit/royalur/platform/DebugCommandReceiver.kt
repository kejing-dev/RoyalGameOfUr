package io.embodit.royalur.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.royalur.domain.Ai
import io.embodit.royalur.domain.ConfirmKind
import io.embodit.royalur.domain.FirstPick
import io.embodit.royalur.domain.GameConfig
import io.embodit.royalur.domain.GameSetup
import io.embodit.royalur.domain.Language
import io.embodit.royalur.domain.Mode
import io.embodit.royalur.domain.Pos
import io.embodit.royalur.domain.Settings
import io.embodit.royalur.domain.Ur
import io.embodit.royalur.ui.Page
import io.embodit.royalur.ui.Ui
import io.embodit.royalur.ui.board.SceneHolder
import io.embodit.royalur.ui.board.route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.royalur.DEBUG -p io.embodit.royalur --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01..S16          reach a named state (AGENTS.md lists what each sets up; S04 / S07 / S08 / S09 / S15 are held by freeze)
 *   roll N                 the next throw shows exactly N white tips (0-4)      cup   pinch the cup (= [掷骰])
 *   tap <collider>         a pinch on a collider: cup, tray, board, sq:B3, pc:0:5, tw:0:0, tw:1:15
 *   pick P                 pinch the near / current side's piece at progress P (0 = start tower)    move   [走这颗]   cancel
 *   new "ai easy me" | "ai smart them" | "2p - random"     setup "<14 hex digits> [side]" (near 7 then far 7, 0..f)
 *   undo · redo · hint on|off · resign · restart · confirm · no · exitask · more on|off · page play|new|rules|settings
 *   lang zh|en|system · tilt 0|15|25 · sound on|off · volume 0-2 · reduced on|off · coach on|off · seed N
 *   freeze MS|off (animations stop at MS, notices hold) · hold on|off (AI waits) · s00hold on|off · welcome
 *   reopen · closepanel · dump|state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun aiGame(first: Int = Ur.NEAR) = flow.newGame(GameConfig(Mode.AI, Ai.EASY, first))
        fun pos(near: List<Int>, far: List<Int>) = Pos(near, far)
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false, coachDone = false) }; flow.freeze(null); flow.start() }
                "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                "S03" -> { flow.freeze(null); aiGame(); Ui.page = Page.PLAY }
                "S04" -> { flow.freeze(450); aiGame(); Ui.page = Page.PLAY; flow.forcedRoll = 3; flow.tapCup() }
                "S05" -> { flow.freeze(null); aiGame(); Ui.page = Page.PLAY; flow.forcedRoll = 3; flow.tapCup() }
                "S06" -> {
                    flow.freeze(null); Ui.page = Page.PLAY
                    flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); flow.setup(pos(listOf(5, 2, 0, 0, 0, 0, 0), listOf(7, 0, 0, 0, 0, 0, 0)), Ur.NEAR)
                    flow.forcedRoll = 2; flow.tapCup(); later(1300) { flow.tapPiece(Ur.NEAR, 5) }
                }
                "S07" -> {
                    // capture in flight: the rival piece at the top of its 12 cm arc (move 220 ms + 260 ms)
                    flow.freeze(null); Ui.page = Page.PLAY
                    flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); flow.setup(pos(listOf(5, 2, 0, 0, 0, 0, 0), listOf(7, 0, 0, 0, 0, 0, 0)), Ur.NEAR)
                    flow.forcedRoll = 2; flow.tapCup()
                    later(1300) { flow.tapPiece(Ur.NEAR, 5); flow.freeze(a.substringAfter(' ', "").toLongOrNull() ?: 480L); flow.moveButton() }
                }
                "S08" -> {
                    flow.freeze(null); Ui.page = Page.PLAY
                    flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); flow.setup(pos(listOf(2, 0, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0, 0)), Ur.NEAR)
                    flow.forcedRoll = 2; flow.tapCup(); later(1300) { flow.tapPiece(Ur.NEAR, 2); flow.tapPiece(Ur.NEAR, 2) }; later(1700) { flow.freeze(60_000) }
                }
                "S09" -> { flow.freeze(null); aiGame(); Ui.page = Page.PLAY; flow.forcedRoll = 0; flow.tapCup(); later(1400) { flow.freeze(60_000) } }
                "S10" -> { flow.freeze(null); flow.holdThinking = true; aiGame(Ur.FAR); Ui.page = Page.PLAY }
                "S11" -> {
                    flow.freeze(null); Ui.page = Page.PLAY
                    flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); flow.setup(pos(listOf(15, 15, 15, 15, 15, 15, 14), listOf(15, 15, 12, 9, 3, 0, 0)), Ur.NEAR)
                    flow.forcedRoll = 1; flow.tapCup(); later(1300) { flow.tapPiece(Ur.NEAR, 14); flow.tapPiece(Ur.NEAR, 14) }
                }
                "S12" -> Ui.page = Page.SETTINGS
                "S13" -> Ui.page = Page.RULES
                "S14" -> { Ui.page = Page.PLAY; flow.requestConfirm(ConfirmKind.RESTART) }
                "S15" -> {
                    flow.freeze(null); Ui.page = Page.PLAY
                    flow.newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR)); flow.setup(pos(listOf(5, 2, 0, 0, 0, 0, 0), listOf(9, 0, 0, 0, 0, 0, 0)), Ur.NEAR)
                    flow.forcedRoll = 2; flow.tapCup(); later(1300) { flow.freeze(60_000); flow.tapPiece(Ur.FAR, 9) }
                }
                "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                else -> Log.w(TAG, "unknown state $a")
            }
            "roll" -> flow.forcedRoll = a.toIntOrNull()?.coerceIn(0, 4)
            "cup" -> flow.tapCup()
            "tap" -> route(graph, a)
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "pick" -> a.toIntOrNull()?.let { flow.tapPiece(flow.state.value.side, it) }
            "move" -> flow.moveButton()
            "cancel" -> flow.cancelPick()
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                flow.newGame(GameSetup(
                    mode = if (f.getOrNull(0) == "2p") Mode.TWO_PLAYER else Mode.AI,
                    aiLevel = if (f.getOrNull(1) == "smart") Ai.SMART else Ai.EASY,
                    first = when (f.getOrNull(2)) { "them" -> FirstPick.THEM; "random" -> FirstPick.RANDOM; else -> FirstPick.ME },
                ))
                Ui.page = Page.PLAY
            }
            "setup" -> {
                val f = a.split(" ")
                val hex = f[0]
                if (hex.length == 14) {
                    val v = hex.map { it.digitToInt(16) }
                    if (flow.state.value.config == null) aiGame()
                    flow.setup(Pos(v.take(7), v.drop(7)), f.getOrNull(1)?.toIntOrNull() ?: Ur.NEAR)
                }
            }
            "undo" -> flow.requestUndo()
            "redo" -> flow.redo()
            "hint" -> flow.setHint(on)
            "resign" -> flow.requestConfirm(ConfirmKind.RESIGN)
            "restart" -> flow.requestConfirm(ConfirmKind.RESTART)
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "more" -> Ui.moreOpen = on
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "tilt" -> a.toIntOrNull()?.takeIf { it in Settings.TILTS }?.let { t -> settings { it.copy(tilt = t) } }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> { settings { it.copy(coachDone = !on, coachRosette = !on, coachCaptured = !on) }; flow.debugState { it.copy(firstGame = on) } }
            "seed" -> a.toIntOrNull()?.let { flow.reseed(it) }
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "hold" -> flow.holdThinking = on
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "welcome" -> flow.welcomeDone()
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
