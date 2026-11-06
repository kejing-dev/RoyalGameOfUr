package io.embodit.royalur.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.royalur.BuildConfig
import io.embodit.royalur.R
import io.embodit.royalur.domain.Ai
import io.embodit.royalur.domain.AlertKind
import io.embodit.royalur.domain.Coach
import io.embodit.royalur.domain.ConfirmKind
import io.embodit.royalur.domain.FirstPick
import io.embodit.royalur.domain.GameSetup
import io.embodit.royalur.domain.Language
import io.embodit.royalur.domain.Mode
import io.embodit.royalur.domain.Phase
import io.embodit.royalur.domain.PlayState
import io.embodit.royalur.domain.Settings
import io.embodit.royalur.domain.Ur
import io.embodit.royalur.platform.Graph
import io.embodit.royalur.platform.TAG
import io.embodit.royalur.ui.Page
import io.embodit.royalur.ui.Ui
import io.embodit.royalur.ui.board.Tip
import io.embodit.royalur.ui.theme.RoyalColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the board): all 2D UI, four pages switched by the native SpatialUI
 * TabBar (D8): Game / New / Rules / Settings. Dragging and closing are the system window controls under it (D21).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the running default game; it never blocks the cup.
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.SETUP -> Ui.page = Page.NEW
            Phase.ROLLING -> if (Ui.page == Page.RULES || Ui.page == Page.NEW) Ui.page = Page.PLAY
            Phase.TO_ROLL, Phase.THINKING -> if (Ui.page == Page.NEW && !Ui.newConfirm) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        listOf(
            Triple(Page.PLAY, R.drawable.ic_tab_play, R.string.tab_play),
            Triple(Page.NEW, R.drawable.ic_tab_new, R.string.tab_new),
            Triple(Page.RULES, R.drawable.ic_tab_rules, R.string.tab_rules),
            Triple(Page.SETTINGS, R.drawable.ic_tab_settings, R.string.tab_settings),
        ).forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = stringResource(label)) },
                supportContent = { Txt(stringResource(label), "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun PageTitle(text: String, tag: String) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

/** Who is "X": in an AI game the user is "You", the AI "Computer · Easy"; two players: near / far. */
@Composable
private fun whoName(state: PlayState, side: Int): String {
    val cfg = state.config ?: return ""
    return when {
        cfg.mode == Mode.TWO_PLAYER -> stringResource(if (side == Ur.NEAR) R.string.who_near else R.string.who_far)
        side == Ur.NEAR -> stringResource(R.string.who_you)
        else -> stringResource(if (cfg.aiLevel == Ai.SMART) R.string.who_ai_smart else R.string.who_ai_easy)
    }
}

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.config == null || state.phase in setOf(Phase.SETUP, Phase.LOADING)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle(stringResource(R.string.tab_play), "play")
            Txt(stringResource(R.string.new_time), "play/empty", maxLines = 2)
            PrimaryButton(stringResource(R.string.btn_start), "play/new", onClick = { Ui.page = Page.NEW })
        }
        return
    }
    val cfg = state.config
    val shown = state.shownPos
    Column(Modifier.fillMaxSize()) {
        // whose turn (same side as the seat tag) + the roll (same source as the dice tag, D38)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.winner == null) {
                SeatChip(state.side, 22.dp)
                Spacer(Modifier.width(10.dp))
                Txt(whoName(state, state.side), "play/turn", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            val roll = state.roll?.takeIf { state.phase != Phase.ROLLING }
            if (roll != null) {
                Txt(roll.value.toString(), "play/roll", style = PicoTheme.typography.titleLarge.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Tip(roll.dice[0], 16); Tip(roll.dice[1], 16) }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Tip(roll.dice[2], 16); Tip(roll.dice[3], 16) }
                }
            }
        }
        StatusLine(state)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.confirm != null -> ConfirmBlock(state.confirm, graph)
                state.winner != null -> ResultBlock(state, graph)
                Ui.moreOpen -> MoreBlock(graph)
                else -> MainAction(state, graph)
            }
        }
        if (state.winner == null && state.confirm == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.canRedo) SecondaryButton(stringResource(R.string.btn_redo), "play/redo", onClick = { flow.redo() }, icon = R.drawable.ic_redo, modifier = Modifier.weight(1f))
                else SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.requestUndo() }, enabled = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
                ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.weight(1.1f))
                ToggleButton(stringResource(R.string.btn_mute), "play/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.weight(0.9f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val off = stringResource(if (cfg.mode == Mode.AI) R.string.off_count_ai else R.string.off_count_2p, shown.off(Ur.NEAR), shown.off(Ur.FAR))
                Txt(off, "play/off", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_more) + if (Ui.moreOpen) " ▴" else " ▾", "play/more", onClick = { Ui.moreOpen = !Ui.moreOpen })
            }
        }
    }
}

/** The main button slot: [掷骰] / grey "tap a lit piece" / [走这颗] + [取消] / grey "they're thinking". */
@Composable
private fun MainAction(state: PlayState, graph: Graph) {
    val flow = graph.flow
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.humansTurn && state.phase == Phase.TO_ROLL ->
                PrimaryButton(stringResource(R.string.btn_roll), "play/roll-btn", onClick = { flow.tapCup(fromPanel = true) }, modifier = Modifier.fillMaxWidth())
            state.humansTurn && state.phase == Phase.PICKED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.btn_move), "play/move-btn", onClick = { flow.moveButton() }, modifier = Modifier.weight(1.4f))
                SecondaryButton(stringResource(R.string.btn_cancel), "play/cancel", onClick = { flow.cancelPick() }, modifier = Modifier.weight(1f).height(56.dp))
            }
            else -> {
                val label = when {
                    state.humansTurn && state.phase == Phase.PICK -> R.string.main_pick
                    state.phase == Phase.ROLLING -> R.string.main_rolling
                    !state.humansTurn -> R.string.main_thinking
                    else -> R.string.main_wait
                }
                // Not a disabled button: grey-on-grey text was 1.9:1 (D13). An ink line on a light pill says what to do.
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(RoyalColors.TagFace), contentAlignment = Alignment.Center) {
                    Txt(stringResource(label), "play/main-grey", style = PicoTheme.typography.titleMedium, color = RoyalColors.InkSecondary)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: PlayState) {
    val alert = state.alert
    val cfg = state.config
    val roll = state.roll?.value ?: 0
    val coachLine = if (state.firstGame && state.humansTurn) when (state.phase) {
        Phase.TO_ROLL -> R.string.coach_1
        Phase.PICK -> R.string.coach_2
        Phase.PICKED -> R.string.coach_3
        else -> null
    } else null
    val text = when {
        alert != null -> stringResource(when (alert.kind) {
            AlertKind.NOT_LIT -> R.string.alert_not_lit
            AlertKind.THEIRS -> R.string.alert_theirs
            AlertKind.TOWER -> R.string.alert_tower
            AlertKind.NOT_TARGET -> R.string.alert_not_target
            AlertKind.ROLL_FIRST -> R.string.alert_roll_first
            AlertKind.MOVE_FIRST -> R.string.alert_move_first
            AlertKind.CUP_HERE -> R.string.alert_cup_here
        })
        state.coach == Coach.CAPTURED -> stringResource(R.string.coach_captured)
        state.coach == Coach.ROSETTE -> stringResource(R.string.coach_rosette)
        state.winner != null -> ""
        state.phase == Phase.AGAIN -> stringResource(if (state.humansTurn) R.string.status_again else R.string.status_again_them)
        state.phase == Phase.NO_MOVE -> when {
            roll == 0 && state.humansTurn -> stringResource(R.string.status_nomove_zero)
            roll == 0 -> stringResource(R.string.status_nomove_zero_them)
            else -> stringResource(R.string.status_nomove)
        }
        state.phase == Phase.MOVING -> stringResource(R.string.status_moving)
        !state.humansTurn && state.roll != null && state.phase != Phase.ROLLING -> stringResource(R.string.status_they_rolled, roll)
        !state.humansTurn -> stringResource(R.string.status_thinking)
        state.phase == Phase.PICKED && state.picked != null -> {
            val m = state.picked
            val cons = when {
                m.captures -> stringResource(R.string.cons_capture)
                m.rosette -> stringResource(R.string.cons_rosette)
                m.off -> stringResource(R.string.cons_off)
                m.from == Ur.START -> stringResource(R.string.cons_enter, m.to - m.from)
                else -> stringResource(R.string.cons_step, m.to - m.from)
            }
            cons + " " + stringResource(coachLine ?: R.string.cons_tap_again)
        }
        coachLine != null -> stringResource(coachLine) + if (state.phase == Phase.PICK && state.hintOn) " " + stringResource(R.string.hint_this) else ""
        state.phase == Phase.PICK -> stringResource(R.string.status_pick, roll) + if (state.hintOn) " " + stringResource(R.string.hint_this) else ""
        state.restored -> stringResource(R.string.status_restored)
        state.phase == Phase.TO_ROLL && cfg?.mode == Mode.TWO_PLAYER -> stringResource(R.string.status_roll_2p, whoName(state, state.side))
        state.phase == Phase.TO_ROLL -> stringResource(R.string.status_roll)
        else -> ""
    }
    val color = if (alert != null) RoyalColors.ErrorText else PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!state.humansTurn && state.phase == Phase.THINKING) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
        }
        Txt(text, "play/status", style = PicoTheme.typography.bodyLarge, color = color, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MoreBlock(graph: Graph) {
    val flow = graph.flow
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.btn_resign), "more/resign", onClick = { Ui.moreOpen = false; flow.requestConfirm(ConfirmKind.RESIGN) }, icon = R.drawable.ic_flag, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.btn_restart), "more/restart", onClick = { Ui.moreOpen = false; flow.requestConfirm(ConfirmKind.RESTART) }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConfirmBlock(kind: ConfirmKind, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val (q, yes) = when (kind) {
        ConfirmKind.RESTART -> R.string.confirm_restart to R.string.confirm_yes_restart
        ConfirmKind.RESIGN -> R.string.confirm_resign to R.string.confirm_yes_resign
        ConfirmKind.EXIT -> R.string.confirm_exit to R.string.confirm_yes_exit
        ConfirmKind.UNDO_2P -> R.string.confirm_undo_2p to R.string.confirm_yes_undo
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(stringResource(q), "confirm/${kind.name}", style = PicoTheme.typography.titleMedium, maxLines = 2)
        // Buttons share the row equally (Cloud Air round 5).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(if (kind == ConfirmKind.UNDO_2P) R.string.btn_never else R.string.btn_cancel), "confirm/cancel", onClick = { flow.cancelConfirm() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(yes), "confirm/yes", danger = kind != ConfirmKind.UNDO_2P, onClick = {
                if (kind == ConfirmKind.EXIT) exitApp(context, graph) else flow.confirm()
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResultBlock(state: PlayState, graph: Graph) {
    val w = state.winner ?: return
    val cfg = state.config ?: return
    val title = stringResource(when {
        cfg.mode == Mode.AI && w == Ur.NEAR -> R.string.result_you_win
        cfg.mode == Mode.AI -> R.string.result_you_lose
        w == Ur.NEAR -> R.string.result_near_win
        else -> R.string.result_far_win
    })
    val secs = state.elapsedMs / 1000
    val time = "%d:%02d".format(secs / 60, secs % 60)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(title, "result/title", style = PicoTheme.typography.titleLarge)
        if (state.resigned) Txt(stringResource(R.string.result_resigned), "result/resigned", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
        Txt(stringResource(R.string.result_stats, state.turnNo, state.captures[w], state.rosettes[w], time), "result/stats", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
        }
        Spacer(Modifier.height(6.dp))
        Txt(stringResource(R.string.result_footnote), "result/footnote", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 4)
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    fun start() { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PageTitle(stringResource(R.string.new_title), "new")
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue), "new/continue", onClick = { graph.flow.continueSaved(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth())
        }
        OptionRow(stringResource(R.string.new_opponent), "new/opponent") {
            Segments(listOf(Mode.AI to stringResource(R.string.new_vs_ai), Mode.TWO_PLAYER to stringResource(R.string.new_2p)), setup.mode, "new/opponent", { m -> set { it.copy(mode = m) } }, Modifier.weight(1f))
        }
        if (setup.mode == Mode.AI) {
            OptionRow(stringResource(R.string.new_level), "new/level") {
                Segments(listOf(Ai.EASY to stringResource(R.string.level_easy), Ai.SMART to stringResource(R.string.level_smart)), setup.aiLevel, "new/level", { l -> set { it.copy(aiLevel = l) } }, Modifier.weight(1f))
            }
        } else {
            Txt(stringResource(R.string.new_2p_note), "new/2p-note", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.height(56.dp).padding(top = 8.dp))
        }
        OptionRow(stringResource(R.string.new_first), "new/first") {
            val ai = setup.mode == Mode.AI
            Segments(
                listOf(
                    FirstPick.ME to stringResource(if (ai) R.string.first_me else R.string.first_near),
                    FirstPick.THEM to stringResource(if (ai) R.string.first_them else R.string.first_far),
                    FirstPick.RANDOM to stringResource(R.string.first_random),
                ),
                setup.first, "new/first", { f -> set { it.copy(first = f) } }, Modifier.weight(1f),
            )
        }
        Txt(stringResource(R.string.new_time), "new/time", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        Spacer(Modifier.height(6.dp))
        if (Ui.newConfirm) {
            // D44: starting over drops the running game, ask first, right here.
            Txt(stringResource(R.string.confirm_new_game), "new/confirm", style = PicoTheme.typography.titleMedium, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "new/confirm-cancel", onClick = { Ui.newConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.confirm_yes_new), "new/confirm-yes", danger = true, onClick = { start() }, modifier = Modifier.weight(1f))
            }
        } else {
            PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
                if (state.hasProgress) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S14") } else start()
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, graph: Graph) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PageTitle(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules")
        RouteDiagram()
        listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEach { Txt(stringResource(it), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 3) }
        Spacer(Modifier.height(2.dp))
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).forEach { Txt("· " + stringResource(it), "rules/mistake", style = PicoTheme.typography.bodySmall, maxLines = 2) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            // [知道了] only switches to the game page (S01 never blocks the cup).
            SecondaryButton(stringResource(R.string.btn_got_it), "rules/got-it", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** The board from above: your route (near row right-to-left, middle lane, back) and the five rosettes. */
@Composable
private fun RouteDiagram() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 264.dp, height = 100.dp)) {
            val cell = size.width / 8f
            val top = (size.height - cell * 3) / 2
            for (r in 0..2) for (c in 1..8) {
                if (r != 1 && c in 5..6) continue
                val o = Offset((c - 1) * cell, top + r * cell)
                drawRoundRect(RoyalColors.Shell, o + Offset(1.5f, 1.5f), Size(cell - 3f, cell - 3f), CornerRadius(4f, 4f))
                val ros = (r != 1 && (c == 1 || c == 7)) || (r == 1 && c == 4)
                if (ros) drawCircle(RoyalColors.Primary, cell * 0.3f, o + Offset(cell / 2, cell / 2))
            }
            fun ctr(r: Int, c: Int) = Offset((c - 0.5f) * cell, top + (r + 0.5f) * cell)
            val path = listOf(ctr(2, 4), ctr(2, 1), ctr(1, 1), ctr(1, 8), ctr(2, 8), ctr(2, 7))
            for (i in 0 until path.size - 1) drawLine(RoyalColors.Jasper, path[i], path[i + 1], strokeWidth = 4f)
            val end = path.last()
            drawCircle(RoyalColors.Jasper, 6f, end, style = Stroke(3f))
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(22.dp, 6.dp)) { drawRect(RoyalColors.Jasper) }
                Spacer(Modifier.width(8.dp))
                Txt(stringResource(R.string.legend_route), "rules/legend-route", style = PicoTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(14.dp)) { drawCircle(RoyalColors.Primary) }
                Spacer(Modifier.width(8.dp))
                Txt(stringResource(R.string.legend_rosette), "rules/legend-rosette", style = PicoTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PageTitle(stringResource(R.string.settings_title), "settings")
        OptionRow(stringResource(R.string.settings_language), "settings/lang") {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_sound), "settings/sound", settings.sound) { v -> upd { it.copy(sound = v) } }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume") {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_reduced), "settings/reduced", settings.reducedMotion) { v -> upd { it.copy(reducedMotion = v) } }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt") {
            Segments(Settings.TILTS.map { it to "$it°" }, settings.tilt, "settings/tilt", { t -> upd { it.copy(tilt = t) } }, Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        // D26: quit is the last row; the confirmation opens right here.
        if (!Ui.exitConfirm) {
            SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S14") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.confirm_exit), "settings/exit-q", style = PicoTheme.typography.titleSmall, modifier = Modifier.weight(1.2f), maxLines = 2)
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-cancel", onClick = { Ui.exitConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.confirm_yes_exit), "settings/exit-yes", onClick = { exitApp(context, graph) }, modifier = Modifier.weight(1f), danger = true)
            }
        }
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

