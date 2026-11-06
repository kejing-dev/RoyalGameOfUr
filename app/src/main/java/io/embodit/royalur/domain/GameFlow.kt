package io.embodit.royalur.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S01 / S12 / S13 / S14 / S15 / S16 are overlays or pages). */
enum class Phase(val id: String) {
    LOADING("S00"), SETUP("S02"), TO_ROLL("S03"), ROLLING("S04"), PICK("S05"), PICKED("S06"), MOVING("S07"),
    AGAIN("S08"), NO_MOVE("S09"), THINKING("S10"), RESULT("S11"),
}

enum class Sound { SHAKE, THROW, LAND, SELECT, PLACE, CAPTURE, ROSETTE, OFF, NOMOVE, ILLEGAL, WIN, LOSE }

/** S15 one-liners. [target] names what flashes red in the volume (an entity tag of the scene). */
enum class AlertKind { NOT_LIT, THEIRS, TOWER, NOT_TARGET, ROLL_FIRST, MOVE_FIRST, CUP_HERE }

data class Alert(val kind: AlertKind, val target: String, val at: Long)

/** The piece in motion (S07): [before] is the position the panel and the tags keep showing until it lands (design R2-2). */
data class MoveAnim(val move: Move, val before: Pos, val startedAt: Long, val durationMs: Long)

/** The dice in the tray: committed [roll], its four precomputed throws, when the throw started, and when they fade out. */
data class DiceShow(val roll: Roll, val throws: List<DiceThrow>, val startedAt: Long, val fadeAt: Long? = null)

enum class ConfirmKind { RESTART, RESIGN, EXIT, UNDO_2P }

enum class Coach { ROSETTE, CAPTURED }

/** The one state every surface reads (board scene, control window, the two tags, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val pos: Pos = Pos.START,
    val side: Int = Ur.NEAR,
    val roll: Roll? = null,
    val dice: DiceShow? = null,
    val legal: List<Move> = emptyList(),
    val picked: Move? = null,
    val anim: MoveAnim? = null,
    val alert: Alert? = null,
    val turnNo: Int = 0,
    val captures: List<Int> = listOf(0, 0),
    val rosettes: List<Int> = listOf(0, 0),
    val last: Move? = null,
    val winner: Int? = null,
    val resigned: Boolean = false,
    val hintOn: Boolean = false,
    val hint: Move? = null,
    val confirm: ConfirmKind? = null,
    val undo: List<Snap> = emptyList(),
    val redo: List<Snap> = emptyList(),
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val welcome: Boolean = false,
    val firstGame: Boolean = false,
    val coach: Coach? = null,
    val restored: Boolean = false,
    val savedAvailable: Boolean = false,
    /** Turn number of the saved game ([继续上一盘 · 第 n 回合]). */
    val savedTurn: Int = 0,
    /** Debug freeze: animations stop this many ms after their start and notices hold. */
    val freezeMs: Long? = null,
    /** When the freeze was set: only animations that start after it are held. */
    val freezeAt: Long = 0,
) {
    val humansTurn: Boolean get() = config?.isHuman(side) == true
    val inGame: Boolean get() = config != null && phase in setOf(Phase.TO_ROLL, Phase.ROLLING, Phase.PICK, Phase.PICKED, Phase.MOVING, Phase.AGAIN, Phase.NO_MOVE, Phase.THINKING)
    val hasProgress: Boolean get() = inGame && (turnNo > 0 || roll != null || undo.isNotEmpty())
    /** The position the tags and counters show: the one before the moving piece lands. */
    val shownPos: Pos get() = anim?.before ?: pos
    /** The side whose undo it is: the user in an AI game, whoever is to move in a two-player game. */
    val undoSide: Int get() = if (config?.mode == Mode.TWO_PLAYER) side else Ur.NEAR
    val canUndo: Boolean get() = humansTurn && phase in setOf(Phase.TO_ROLL, Phase.PICK, Phase.PICKED) && undoTarget() != null
    val canRedo: Boolean get() = redo.isNotEmpty() && humansTurn && phase in setOf(Phase.TO_ROLL, Phase.PICK, Phase.PICKED)

    fun snap(withRoll: Boolean): Snap = Snap(pos, side, if (withRoll) roll else null, turnNo, captures, rosettes, last)

    /** Index in [undo] of the snapshot an undo goes back to, or null. */
    fun undoTarget(): Int? {
        val current = snap(true)
        for (i in undo.indices.reversed()) {
            val s = undo[i]
            if (s.side != undoSide) continue
            if ((phase == Phase.PICK || phase == Phase.PICKED) && s == current) continue
            return i
        }
        return null
    }
}

/**
 * Turn sequencing (rules - state - render: this is the middle layer). Main-thread confined: every public call and
 * every coroutine it launches runs on [scope]'s dispatcher; dice physics and the AI run on [engineDispatcher].
 * Debug commands call the same functions the volume and the window call (profile D29).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
    seed: Int = 20261105,
) {
    companion object {
        const val ROLL_MS = 900L
        const val ROLL_REDUCED_MS = 200L
        const val STEP_MS = 220L
        const val OFF_MS = 300L
        const val CAPTURE_MS = 520L
        const val REDUCED_MOVE_MS = 150L
        const val AGAIN_MS = 600L
        const val NO_MOVE_MS = 900L
        const val ALERT_MS = 1600L
        const val MIN_THINK_MS = 400L
        const val AI_LIT_MS = 300L
        const val AI_PRE_ROLL_MS = 350L
        const val DICE_FADE_MS = 300L
        const val UNDO_LIMIT = 120
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private var rng = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** A window-only confirmation (exit / new game) is open: the volume is locked too (design input.confirmLock). */
    @Volatile var uiLocked: Boolean = false
    /** Debug `roll N`: the next throw shows exactly N white tips. */
    var forcedRoll: Int? = null

    private var turnJob: Job? = null
    private var alertJob: Job? = null
    private var coachJob: Job? = null
    /** The logical snapshot written while a piece is in flight (the move is already committed). */
    private var pendingSnap: Snap? = null

    private fun set(next: PlayState) { _state.value = next }

    private val reduced: Boolean get() = settings().reducedMotion

    // ---- lifecycle ----

    /** S00 exits: first run -> default game + rules page (S01); a save -> S16; otherwise S02. */
    fun start() {
        val saved = store.load()
        val st = settings()
        if (!st.firstRunDone) {
            newGame(GameConfig(Mode.AI, Ai.EASY, Ur.NEAR))
            set(s.copy(welcome = true, savedAvailable = false))
            log("STATE S01 (default game, rules page open)")
            return
        }
        if (saved != null && Ur.winner(saved.now.pos) == null) restore(saved)
        else { set(PlayState(phase = Phase.SETUP)); log("STATE S02") }
    }

    /** [知道了] on the rules page / the first pinch on the cup: the welcome is over, the game was already running. */
    fun welcomeDone() {
        if (!s.welcome && settings().firstRunDone) return
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(welcome = false))
        log("WELCOME done")
    }

    fun continueSaved(): Boolean {
        val saved = store.load() ?: return false
        if (Ur.winner(saved.now.pos) != null) return false
        restore(saved)
        return true
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        val n = saved.now
        set(PlayState(
            phase = Phase.TO_ROLL, config = saved.config, pos = n.pos, side = n.side, turnNo = n.turnNo, captures = n.captures,
            rosettes = n.rosettes, last = n.last, undo = saved.undo, elapsedMs = saved.elapsedMs, resumedAt = clock(),
            hintOn = saved.hintOn, restored = true, savedAvailable = true,
        ))
        log("GAME restored side=${n.side} turn=${n.turnNo} roll=${n.roll?.value} S16")
        val roll = n.roll
        if (roll == null) { nextTurn(); return }
        // pendingRoll: the dice are put straight into their resting poses and the legal moves are recomputed.
        turnJob = scope.launch {
            val throws = computeThrows(roll)
            set(s.copy(roll = roll, dice = DiceShow(roll, throws, clock() - 60_000)))
            afterRoll()
        }
    }

    fun newGame(setup: GameSetup) {
        val first = when (setup.first) { FirstPick.ME -> Ur.NEAR; FirstPick.THEM -> Ur.FAR; FirstPick.RANDOM -> if (rng.nextBoolean()) Ur.NEAR else Ur.FAR }
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.mode, setup.aiLevel.coerceIn(1, 2), first))
    }

    fun newGame(config: GameConfig) {
        cancelJobs()
        set(PlayState(
            phase = Phase.TO_ROLL, config = config, side = config.firstSide, resumedAt = clock(),
            firstGame = !settings().coachDone, hintOn = s.hintOn, welcome = s.welcome, freezeMs = s.freezeMs, freezeAt = s.freezeAt,
        ))
        log("GAME new mode=${config.mode} level=${config.aiLevel} first=${config.firstSide}")
        persist()
        nextTurn()
    }

    /** [再来一局]: same setup, the other side starts. */
    fun again() { s.config?.let { newGame(it.copy(firstSide = Ur.other(it.firstSide))) } }

    fun toMenu() {
        cancelJobs()
        val saved = store.load()
        set(PlayState(phase = Phase.SETUP, savedAvailable = saved != null, savedTurn = saved?.now?.turnNo ?: 0, hintOn = s.hintOn))
        log("STATE S02")
    }

    /** Going to the background: stop the AI, land a piece in flight, keep the game. */
    fun onBackground() {
        if (s.anim != null) { turnJob?.cancel(); afterMove() }
        if (s.phase == Phase.THINKING || s.phase == Phase.ROLLING) turnJob?.cancel()
        persist()
        log("BACKGROUND phase=${s.phase.id}")
    }

    fun onForeground() {
        val st = s
        if (st.config == null || turnJob?.isActive == true) return
        when (st.phase) {
            Phase.THINKING -> if (st.roll == null) aiTurn() else afterRoll()
            Phase.ROLLING -> afterRoll()
            else -> Unit
        }
    }

    // ---- input (volume entities, panel buttons, debug) ----

    /** A pinch / trigger on the cup, or the panel's [掷骰]: one path (design T1). */
    fun tapCup(fromPanel: Boolean = false) {
        val st = s
        log("TAP cup phase=${st.phase.id} panel=$fromPanel")
        if (st.confirm != null || uiLocked) { log("LOCKED by confirm (S14)"); return }
        if (!st.humansTurn) return
        when (st.phase) {
            Phase.TO_ROLL -> { if (st.confirm == null) roll() }
            Phase.PICK, Phase.PICKED -> alert(AlertKind.MOVE_FIRST, "cup")
            else -> Unit
        }
    }

    fun tapTray() {
        val st = s
        log("TAP tray phase=${st.phase.id}")
        if (st.confirm != null || uiLocked) { log("LOCKED by confirm (S14)"); return }
        if (!st.humansTurn) return
        when (st.phase) {
            Phase.TO_ROLL -> alert(AlertKind.CUP_HERE, "cup")
            Phase.PICKED -> cancelPick()
            else -> Unit
        }
    }

    /** A piece of [side] at progress [p] (0 = its start tower, any layer; 15 = its finish tower). */
    fun tapPiece(side: Int, p: Int) {
        val st = s
        log("TAP piece side=$side p=$p phase=${st.phase.id} picked=${st.picked?.from}")
        if (st.confirm != null || uiLocked) { log("LOCKED by confirm (S14)"); return }
        if (!st.humansTurn) return
        val tag = tagOf(side, p)
        when (st.phase) {
            Phase.TO_ROLL -> alert(AlertKind.ROLL_FIRST, tag)
            Phase.PICK, Phase.PICKED -> {
                val picked = st.picked
                if (side != st.side) {
                    // the rival piece sitting on the target square confirms the capture (critic #4)
                    if (picked != null && picked.captures && p == picked.to) commit(picked) else alert(AlertKind.THEIRS, tag)
                    return
                }
                if (p == Ur.OFF) {
                    if (picked != null && picked.off) commit(picked) else alert(AlertKind.TOWER, tag)
                    return
                }
                val m = st.legal.firstOrNull { it.from == p }
                when {
                    m == null -> alert(if (p == Ur.START && st.pos.inStart(side) == 0) AlertKind.TOWER else AlertKind.NOT_LIT, tag)
                    picked != null && picked.from == p -> commit(picked)
                    else -> select(m)
                }
            }
            else -> Unit
        }
    }

    /** A pinch on an empty playing square (squares with a piece route to [tapPiece]). */
    fun tapSquare(sq: Sq) {
        val st = s
        if (st.confirm != null || uiLocked) { log("LOCKED by confirm (S14)"); return }
        st.pos.at(sq)?.let { (side, i) -> tapPiece(side, st.pos.of(side)[i]); return }
        log("TAP square ${sq.name} phase=${st.phase.id}")
        if (!st.humansTurn) return
        when (st.phase) {
            Phase.PICKED -> {
                val m = st.picked!!
                if (!m.off && Ur.square(m.side, m.to) == sq) commit(m) else alert(AlertKind.NOT_TARGET, "sq:${sq.name}")
            }
            Phase.PICK -> alert(AlertKind.NOT_LIT, "sq:${sq.name}")
            Phase.TO_ROLL -> alert(AlertKind.ROLL_FIRST, "cup")
            else -> Unit
        }
    }

    /** A pinch on the board frame / anywhere that is not a square, piece, tower or the cup: back to S05. */
    fun tapEmpty() {
        if (s.confirm != null || uiLocked) return
        log("TAP empty phase=${s.phase.id}")
        if (s.phase == Phase.PICKED && s.humansTurn) cancelPick()
    }

    /** Control-window pick button (T14): exactly a pinch on that piece in the volume (same path, same state). */
    fun panelPick(m: Move) { log("PANEL pick ${m.from}>${m.to}"); tapPiece(m.side, m.from) }

    /** Control-window [走 · 后果] when only one move is legal: select + confirm in one press. */
    fun panelQuickMove() {
        val m = s.legal.singleOrNull() ?: return
        log("PANEL quick ${m.from}>${m.to}")
        if (s.phase == Phase.PICK) tapPiece(m.side, m.from)
        if (s.phase == Phase.PICKED) tapPiece(m.side, m.from)
    }

    /** Panel [走这颗]. */
    fun moveButton() { val m = s.picked; if (s.phase == Phase.PICKED && s.humansTurn && m != null) commit(m) }

    fun cancelPick() {
        if (s.phase != Phase.PICKED) return
        set(s.copy(phase = Phase.PICK, picked = null))
        log("CANCEL -> S05")
    }

    private fun select(m: Move) {
        set(s.copy(phase = Phase.PICKED, picked = m, alert = null))
        onSound(Sound.SELECT)
        log("PICK from=${m.from} to=${m.to} cap=${m.captures} ros=${m.rosette} off=${m.off} S06")
    }

    private fun tagOf(side: Int, p: Int): String = when (p) {
        Ur.START -> "tw:$side:0"
        Ur.OFF -> "tw:$side:15"
        else -> "sq:${Ur.square(side, p).name}"
    }

    private fun alert(kind: AlertKind, target: String) {
        val at = clock()
        set(s.copy(alert = Alert(kind, target, at)))
        onSound(Sound.ILLEGAL)
        log("ALERT $kind $target S15")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            awaitUnfrozen()
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    // ---- rolling ----

    private suspend fun computeThrows(roll: Roll): List<DiceThrow> =
        withContext(engineDispatcher) { roll.dice.mapIndexed { i, white -> Tetra.createThrow(white, roll.seeds[i]) } }

    private fun roll() {
        if ((s.welcome || !settings().firstRunDone) && s.humansTurn) welcomeDone()
        val st = s
        val roll = forcedRoll?.let { Roll.forced(it, rng) } ?: Roll.random(rng)
        forcedRoll = null
        set(st.copy(phase = Phase.ROLLING, roll = roll, redo = if (st.humansTurn) emptyList() else st.redo, alert = null, hint = null, restored = false, coach = null))
        log("ROLL side=${st.side} value=${roll.value} dice=${roll.dice.joinToString("") { if (it) "1" else "0" }} S04")
        persist()
        turnJob?.cancel()
        turnJob = scope.launch {
            val throws = computeThrows(roll)
            onSound(Sound.SHAKE)
            set(s.copy(dice = DiceShow(roll, throws, clock())))
            delay(150)
            onSound(Sound.THROW)
            delay((if (reduced) ROLL_REDUCED_MS else ROLL_MS) - 150)
            awaitUnfrozen()
            onSound(Sound.LAND)
            afterRoll()
        }
    }

    private fun afterRoll() {
        val st = s
        val roll = st.roll ?: return
        val legal = Ur.legalMoves(st.pos, st.side, roll.value)
        log("LANDED value=${roll.value} legal=${legal.joinToString(",") { "${it.from}>${it.to}" }}")
        if (legal.isEmpty()) {
            set(st.copy(phase = Phase.NO_MOVE, legal = emptyList(), picked = null))
            onSound(Sound.NOMOVE)
            log("NOMOVE value=${roll.value} S09")
            turnJob = scope.launch {
                delay(NO_MOVE_MS)
                awaitUnfrozen()
                endTurn()
            }
            return
        }
        if (st.humansTurn) {
            val snap = st.snap(true)
            val undo = (if (st.undo.lastOrNull() == snap) st.undo else st.undo + snap).takeLast(UNDO_LIMIT)
            set(st.copy(phase = Phase.PICK, legal = legal, picked = null, undo = undo))
            log("STATE S05 legal=${legal.size}")
            refreshHint()
            persist()
        } else {
            set(st.copy(phase = Phase.THINKING, legal = legal))
            aiChoose()
        }
    }

    // ---- moving ----

    private fun commit(m: Move) {
        val st = s
        val before = st.pos
        val after = Ur.apply(before, m)
        val captures = st.captures.toMutableList().also { if (m.captures) it[m.side]++ }
        val rosettes = st.rosettes.toMutableList().also { if (m.rosette) it[m.side]++ }
        val dur = if (reduced) REDUCED_MOVE_MS else (if (m.off) OFF_MS else STEP_MS) + (if (m.captures) CAPTURE_MS else 0L)
        set(st.copy(
            phase = Phase.MOVING, pos = after, picked = null, legal = emptyList(), hint = null, alert = null,
            anim = MoveAnim(m, before, clock(), dur), captures = captures, rosettes = rosettes, last = m,
            redo = if (st.humansTurn) emptyList() else st.redo, coach = null,
        ))
        log("MOVE side=${m.side} piece=${m.piece} ${m.from}>${m.to} cap=${m.captured} ros=${m.rosette} off=${m.off} by=${if (st.humansTurn) "human" else "ai"} S07")
        // The move is committed and saved before the animation (Seed Pits pattern).
        val win = Ur.winner(after)
        pendingSnap = when {
            win != null -> null
            m.rosette -> Snap(after, m.side, null, st.turnNo, captures, rosettes, m)
            else -> Snap(after, Ur.other(m.side), null, st.turnNo + 1, captures, rosettes, m)
        }
        if (win == null) persist() else store.clear()
        turnJob?.cancel()
        turnJob = scope.launch {
            val land = if (reduced) dur else (if (m.off) OFF_MS else STEP_MS)
            delay(land)
            onSound(if (m.off) Sound.OFF else Sound.PLACE)
            if (m.captures && !reduced) { delay(60); onSound(Sound.CAPTURE); delay(dur - land - 60) }
            awaitUnfrozen()
            afterMove()
        }
    }

    private fun afterMove() {
        val st = s
        val m = st.anim?.move ?: return
        pendingSnap = null
        set(st.copy(anim = null))
        Ur.winner(st.pos)?.let { finish(it, resigned = false); return }
        val cfg = st.config ?: return
        // First time the user's piece is caught (coach line).
        if (m.captures && cfg.mode == Mode.AI && !cfg.isHuman(m.side) && !settings().coachCaptured) {
            onSettings(settings().copy(coachCaptured = true)); showCoach(Coach.CAPTURED)
        }
        if (m.rosette) {
            if (cfg.isHuman(m.side) && !settings().coachRosette) { onSettings(settings().copy(coachRosette = true)); showCoach(Coach.ROSETTE) }
            set(s.copy(phase = Phase.AGAIN, roll = null, dice = s.dice?.let { it.copy(fadeAt = clock()) }))
            onSound(Sound.ROSETTE)
            log("AGAIN side=${m.side} S08")
            turnJob = scope.launch {
                delay(AGAIN_MS)
                awaitUnfrozen()
                nextTurn()
            }
            return
        }
        endTurn()
    }

    private fun showCoach(c: Coach) {
        set(s.copy(coach = c))
        coachJob?.cancel()
        coachJob = scope.launch { delay(5000); if (s.coach == c) set(s.copy(coach = null)) }
    }

    private fun endTurn() {
        val st = s
        set(st.copy(side = Ur.other(st.side), turnNo = st.turnNo + 1, roll = null, legal = emptyList(), picked = null,
            dice = st.dice?.let { if (it.fadeAt == null) it.copy(fadeAt = clock()) else it }))
        log("TURN -> side=${s.side} turn=${s.turnNo}")
        persist()
        nextTurn()
    }

    private fun nextTurn() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        if (cfg.isHuman(st.side)) {
            set(st.copy(phase = Phase.TO_ROLL, roll = null, legal = emptyList(), picked = null))
            log("STATE S03 side=${st.side}")
            persist()
        } else aiTurn()
    }

    // ---- AI ----

    private fun aiTurn() {
        set(s.copy(phase = Phase.THINKING, roll = null, legal = emptyList(), picked = null))
        log("THINK pre-roll S10")
        turnJob?.cancel()
        turnJob = scope.launch {
            delay(AI_PRE_ROLL_MS)
            while (holdThinking) delay(100)
            if (s.phase == Phase.THINKING && s.roll == null) roll()
        }
    }

    private fun aiChoose() {
        val st = s
        val cfg = st.config ?: return
        val pos = st.pos
        val legal = st.legal
        turnJob = scope.launch {
            val t0 = clock()
            val pick = withContext(engineDispatcher) { Ai.choose(pos, legal, cfg.aiLevel, Random(aiSeed + st.turnNo * 31 + st.side)) } ?: return@launch
            val waited = clock() - t0
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (s.phase != Phase.THINKING || s.pos != pos) return@launch
            set(s.copy(picked = pick))
            log("AI pick ${pick.from}>${pick.to} level=${cfg.aiLevel}")
            delay(AI_LIT_MS)
            if (s.phase != Phase.THINKING || s.pos != pos) return@launch
            commit(pick)
        }
    }

    // ---- hint, undo / redo ----

    private fun refreshHint() {
        val st = s
        if (!st.hintOn || !st.humansTurn || st.phase !in setOf(Phase.PICK, Phase.PICKED)) { if (st.hint != null) set(st.copy(hint = null)); return }
        val h = Ai.choose(st.pos, st.legal, Ai.SMART, Random(aiSeed + st.turnNo))
        set(st.copy(hint = h))
        log("HINT ${h?.from}>${h?.to}")
    }

    fun setHint(on: Boolean) {
        set(s.copy(hintOn = on, hint = if (on) s.hint else null))
        log("HINT ${if (on) "on" else "off"}")
        refreshHint()
        persist()
    }

    fun requestUndo() {
        val cfg = s.config ?: return
        if (!s.canUndo) { log("UNDO not available"); return }
        if (cfg.mode == Mode.TWO_PLAYER) requestConfirm(ConfirmKind.UNDO_2P) else undo()
    }

    private fun undo() {
        val st = s
        val i = st.undoTarget() ?: return
        cancelJobs()
        val target = st.undo[i]
        val redo = listOf(st.snap(st.phase == Phase.PICK || st.phase == Phase.PICKED)) + st.redo
        restoreSnap(target, st.undo.take(i + 1), redo)
        log("UNDO -> turn=${target.turnNo} roll=${target.roll?.value} redo=${redo.size}")
    }

    fun redo() {
        val st = s
        if (!st.canRedo) return
        cancelJobs()
        val target = st.redo.first()
        restoreSnap(target, st.undo, st.redo.drop(1))
        log("REDO -> turn=${target.turnNo} roll=${target.roll?.value}")
    }

    /** Puts the game at [snap]: S05 with the dice at rest when it carries a roll, otherwise S03. */
    private fun restoreSnap(snap: Snap, undo: List<Snap>, redo: List<Snap>) {
        val st = s
        set(st.copy(
            pos = snap.pos, side = snap.side, roll = snap.roll, turnNo = snap.turnNo, captures = snap.captures, rosettes = snap.rosettes,
            last = snap.last, undo = undo, redo = redo, picked = null, anim = null, alert = null, confirm = null, hint = null,
            legal = emptyList(), dice = null, phase = Phase.TO_ROLL,
        ))
        val roll = snap.roll
        if (roll == null) { nextTurn(); return }
        turnJob = scope.launch {
            val throws = computeThrows(roll)
            set(s.copy(dice = DiceShow(roll, throws, clock() - 60_000)))
            val legal = Ur.legalMoves(s.pos, s.side, roll.value)
            val keep = if (s.undo.lastOrNull() == snap) s.undo else s.undo + snap
            set(s.copy(phase = Phase.PICK, legal = legal, undo = keep))
            log("STATE S05 (restored) legal=${legal.size}")
            refreshHint()
            persist()
        }
    }

    // ---- confirm dialogs ----

    fun requestConfirm(kind: ConfirmKind) { set(s.copy(confirm = kind)); log("CONFIRM $kind S14") }

    fun cancelConfirm() { set(s.copy(confirm = null)); log("CONFIRM cancel") }

    /** Confirms the open dialog. EXIT is handled by the window (it owns the navigator); it only saves here. */
    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        log("CONFIRM yes $kind")
        when (kind) {
            ConfirmKind.UNDO_2P -> undo()
            ConfirmKind.RESIGN -> {
                val cfg = s.config ?: return
                val loser = if (cfg.mode == Mode.AI) Ur.NEAR else s.side
                finish(Ur.other(loser), resigned = true)
            }
            ConfirmKind.RESTART -> { cancelJobs(); store.clear(); set(PlayState(phase = Phase.SETUP, hintOn = s.hintOn)); log("STATE S02") }
            ConfirmKind.EXIT -> onBackground()
        }
    }

    private fun finish(winner: Int, resigned: Boolean) {
        cancelJobs()
        val st = s
        set(st.copy(phase = Phase.RESULT, winner = winner, resigned = resigned, elapsedMs = st.elapsedMs + (clock() - st.resumedAt),
            anim = null, picked = null, legal = emptyList(), hint = null, confirm = null, roll = null))
        val cfg = st.config
        // The opponent's win is not a fanfare (D44).
        val aiWon = cfg != null && cfg.mode == Mode.AI && winner == Ur.FAR
        onSound(if (aiWon) Sound.LOSE else Sound.WIN)
        log("RESULT winner=$winner resigned=$resigned turns=${st.turnNo} S11")
        if (st.firstGame) onSettings(settings().copy(coachDone = true))
        store.clear()
    }

    // ---- debug ----

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms, freezeAt = clock())); log("FREEZE ${ms ?: "off"}") }

    private suspend fun awaitUnfrozen() { while (coroutineContext.isActive && s.freezeMs != null) delay(50) }

    /** Debug `setup`: replace the position (side to move keeps its config), back to S03. */
    fun setup(pos: Pos, side: Int = s.side) {
        val cfg = s.config ?: GameConfig()
        cancelJobs()
        set(s.copy(config = cfg, pos = pos, side = side, roll = null, dice = null, legal = emptyList(), picked = null, anim = null,
            winner = null, phase = Phase.TO_ROLL, undo = emptyList(), redo = emptyList(), confirm = null, alert = null))
        log("SETUP ${SaveCodec.snap(s.snap(false))}")
        persist()
        nextTurn()
    }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun reseed(seed: Int) { rng = Random(seed); aiSeed = seed; log("SEED $seed") }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} side=${st.side} turn=${st.turnNo} roll=${st.roll?.value} pos=${SaveCodec.snap(st.snap(false)).substringBefore(',')} " +
            "legal=${st.legal.joinToString(",") { "${it.from}>${it.to}" }} picked=${st.picked?.let { "${it.from}>${it.to}" }} hint=${st.hint?.let { "${it.from}>${it.to}" }} " +
            "alert=${st.alert?.kind} confirm=${st.confirm} undo=${st.undo.size} redo=${st.redo.size} welcome=${st.welcome} caps=${st.captures} ros=${st.rosettes} winner=${st.winner} freeze=${st.freezeMs}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        val snap = when {
            st.anim != null -> pendingSnap ?: return
            st.phase in setOf(Phase.ROLLING, Phase.PICK, Phase.PICKED, Phase.NO_MOVE) || (st.phase == Phase.THINKING && st.roll != null) -> st.snap(true)
            else -> st.snap(false)
        }
        store.save(SavedGame(cfg, snap, st.undo, st.elapsedMs + (clock() - st.resumedAt), st.hintOn))
    }

    private fun cancelJobs() {
        turnJob?.cancel(); alertJob?.cancel(); coachJob?.cancel()
        pendingSnap = null
    }
}
