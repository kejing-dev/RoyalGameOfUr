package io.embodit.royalur.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
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
import io.embodit.royalur.domain.Move
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
 * TabBar (D8): Game / New / Rules / Settings. Design v1.2: every page has the same header with [静音], its content is
 * vertically centred (blank <= 25 % of the window), confirmations are a whole-page card, and the game page has one button
 * per movable piece (T14) that goes through the same GameFlow calls as a pinch on the piece in the volume.
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
    LaunchedEffect(Ui.exitConfirm, Ui.newConfirm) { graph.flow.uiLocked = Ui.exitConfirm || Ui.newConfirm }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(state, settings, graph)
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

/** Every page: the same skeleton, content centred vertically, blocks 16 dp apart. */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), content = content)
}

/** Page header (56): a title (or the seat chip on the game page), a small mode line and [静音] on every page. */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph, side: Int? = null, sub: String? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (side != null) { SeatChip(side, 22.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        }
        ToggleButton(stringResource(R.string.btn_mute), "$tag/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } })
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

@Composable
private fun modeLine(state: PlayState): String {
    val cfg = state.config ?: return ""
    return if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.mode_2p) else stringResource(if (cfg.aiLevel == Ai.SMART) R.string.who_ai_smart else R.string.who_ai_easy)
}

@Composable
private fun turnTitle(state: PlayState): String {
    val cfg = state.config ?: return stringResource(R.string.seat_ready)
    return when {
        cfg.mode == Mode.TWO_PLAYER -> stringResource(if (state.side == Ur.NEAR) R.string.seat_near else R.string.seat_far)
        state.side == Ur.NEAR -> stringResource(R.string.seat_you)
        else -> stringResource(R.string.seat_them)
    }
}

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.config == null || state.phase in setOf(Phase.SETUP, Phase.LOADING)) {
        PageColumn {
            Header(stringResource(R.string.tab_play), "play", settings, graph)
            Txt(stringResource(R.string.new_time), "play/empty", maxLines = 2)
            PrimaryButton(stringResource(R.string.btn_start), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val confirm = state.confirm
    if (confirm != null) { ConfirmPage(confirm, settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }); return }
    if (state.winner != null) { ResultPage(state, settings, graph); return }
    PageColumn {
        Header(turnTitle(state), "play", settings, graph, side = state.side, sub = modeLine(state))
        DiceBox(state)
        when (state.phase) {
            Phase.TO_ROLL, Phase.ROLLING, Phase.AGAIN -> if (state.humansTurn) {
                RollButton(state, graph)
                PieceStats(state)
            } else { NoticeCard(state); PieceStats(state) }
            Phase.PICK, Phase.PICKED, Phase.MOVING -> if (state.humansTurn || state.phase == Phase.MOVING && state.anim?.move?.let { state.config.isHuman(it.side) } == true) {
                PickGrid(state, graph)
                MainButton(state, graph)
            } else { NoticeCard(state); PieceStats(state) }
            else -> { NoticeCard(state); PieceStats(state) }
        }
        if (Ui.moreOpen) MoreRow(graph) else SecondaryRow(state, graph)
    }
}

/** Sentence under the big number (status, alerts, coach lines). */
@Composable
private fun statusText(state: PlayState): String {
    val alert = state.alert
    val roll = state.roll?.value ?: 0
    val coach = if (state.firstGame && state.humansTurn) when (state.phase) {
        Phase.TO_ROLL -> R.string.coach_1
        Phase.PICK -> R.string.coach_2
        Phase.PICKED -> R.string.coach_3
        else -> null
    } else null
    return when {
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
        state.phase == Phase.AGAIN -> stringResource(if (state.humansTurn) R.string.status_again else R.string.status_again_them)
        state.phase == Phase.NO_MOVE -> when {
            roll == 0 && state.humansTurn -> stringResource(R.string.status_nomove_zero)
            roll == 0 -> stringResource(R.string.status_nomove_zero_them)
            else -> stringResource(R.string.status_nomove)
        }
        state.phase == Phase.MOVING -> stringResource(R.string.status_moving)
        !state.humansTurn && state.roll != null && state.phase != Phase.ROLLING -> stringResource(R.string.status_they_rolled, roll)
        !state.humansTurn -> stringResource(R.string.status_thinking)
        state.phase == Phase.ROLLING -> stringResource(R.string.main_rolling)
        state.phase == Phase.PICKED && state.picked != null -> consequence(state.picked) + " " + stringResource(coach ?: R.string.cons_tap_again)
        coach != null -> stringResource(coach)
        state.phase == Phase.PICK -> stringResource(R.string.dice_line_pick, roll) + if (state.hintOn) " " + stringResource(R.string.hint_this) else ""
        state.restored -> stringResource(R.string.status_restored)
        state.phase == Phase.TO_ROLL && state.config?.mode == Mode.TWO_PLAYER -> stringResource(R.string.status_roll_2p, whoName(state, state.side))
        state.phase == Phase.TO_ROLL -> stringResource(R.string.status_roll)
        else -> ""
    }
}

@Composable
private fun consequence(m: Move): String = when {
    m.captures -> stringResource(R.string.cons_capture)
    m.rosette -> stringResource(R.string.cons_rosette)
    m.off -> stringResource(R.string.cons_off)
    m.from == Ur.START -> stringResource(R.string.cons_enter, m.to - m.from)
    else -> stringResource(R.string.cons_step, m.to - m.from)
}

/** Short label on a pick button. */
@Composable
private fun shortLabel(m: Move): String = when {
    m.off -> stringResource(R.string.pick_off)
    m.captures -> stringResource(R.string.pick_capture)
    m.rosette -> stringResource(R.string.pick_rosette)
    m.from == Ur.START -> stringResource(R.string.pick_enter)
    else -> stringResource(R.string.pick_step, m.to - m.from)
}

/** Dice box (96): the roll (same source as the dice tag, D38), its four tips, one sentence. */
@Composable
private fun DiceBox(state: PlayState) {
    val roll = state.roll?.takeIf { state.phase != Phase.ROLLING }
    Row(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).background(RoyalColors.TagFace).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(roll?.value?.toString() ?: "–", "play/roll", style = PicoTheme.typography.titleLarge.copy(fontSize = 56.sp, fontWeight = FontWeight.Bold), color = RoyalColors.Ink, modifier = Modifier.width(44.dp), align = TextAlign.Center)
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val d = roll?.dice
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Tip(d?.get(0) ?: false, 16); Tip(d?.get(1) ?: false, 16) }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Tip(d?.get(2) ?: false, 16); Tip(d?.get(3) ?: false, 16) }
        }
        Spacer(Modifier.width(16.dp))
        val color = if (state.alert != null) RoyalColors.ErrorText else RoyalColors.Ink
        Txt(statusText(state), "play/status", style = PicoTheme.typography.bodyLarge, color = color, maxLines = 3, modifier = Modifier.weight(1f))
    }
}

/** S03 / S04 / S08: the big [掷骰] (132) with its sub-line; the same path as a pinch on the cup. */
@Composable
private fun RollButton(state: PlayState, graph: Graph) {
    val enabled = state.phase == Phase.TO_ROLL
    Button(
        onClick = { graph.flow.tapCup(fromPanel = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        size = ButtonDefaults.Regular.copy(height = 132.dp, minWidth = 200.dp), shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (enabled) PicoTheme.colorScheme.interaction else RoyalColors.TagFace, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // design-style: inherited-content-color Button
            Txt(stringResource(if (enabled) R.string.btn_roll else if (state.phase == Phase.AGAIN) R.string.main_wait else R.string.main_rolling), "play/roll-btn",
                style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp), color = if (enabled) RoyalColors.OnPrimary else RoyalColors.InkSecondary)
            Txt(stringResource(R.string.roll_sub), "play/roll-sub", style = PicoTheme.typography.bodyMedium, color = if (enabled) RoyalColors.OnPrimary else RoyalColors.InkSecondary)
        }
    }
}

/** "Your pieces" (76): in tower / on board / off, for the side to move. */
@Composable
private fun PieceStats(state: PlayState) {
    val pos = state.shownPos
    val side = if (state.config?.mode == Mode.TWO_PLAYER) state.side else Ur.NEAR
    val title = stringResource(when { state.config?.mode != Mode.TWO_PLAYER -> R.string.your_pieces; side == Ur.NEAR -> R.string.near_pieces; else -> R.string.far_pieces })
    Column(Modifier.fillMaxWidth().height(76.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(title, "play/stats-title", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell(stringResource(R.string.stat_tower), pos.inStart(side).toString(), Modifier.weight(1f))
            StatCell(stringResource(R.string.stat_board), pos.onBoard(side).toString(), Modifier.weight(1f))
            StatCell(stringResource(R.string.stat_off), "${pos.off(side)}/7", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(RoyalColors.TagFace).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "play/stat", style = PicoTheme.typography.bodyMedium, color = RoyalColors.InkSecondary, modifier = Modifier.weight(1f))
        Txt(value, "play/stat-v", style = PicoTheme.typography.titleMedium, color = RoyalColors.Ink)
    }
}

/** S09 / S10: a notice card (132). */
@Composable
private fun NoticeCard(state: PlayState) {
    Row(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(28.dp)).background(RoyalColors.TagFace).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!state.humansTurn && state.phase in setOf(Phase.THINKING, Phase.ROLLING)) { CircularProgressIndicator(modifier = Modifier.size(32.dp)); Spacer(Modifier.width(16.dp)) }
        val text = when {
            state.phase == Phase.NO_MOVE -> statusText(state)
            !state.humansTurn -> whoName(state, state.side) + " · " + statusText(state)
            else -> statusText(state)
        }
        Txt(text, "play/notice", style = PicoTheme.typography.titleMedium, color = RoyalColors.Ink, maxLines = 3, modifier = Modifier.weight(1f))
    }
}

/** T14: one button per movable piece (back to front), 4 columns × 1–2 rows of 72 dp. */
@Composable
private fun PickGrid(state: PlayState, graph: Graph) {
    val moves = state.legal.ifEmpty { listOfNotNull(state.anim?.move) }
    val rows = moves.chunked(4)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> Box(Modifier.weight(1f)) { PickButton(m, state, graph, Modifier.fillMaxWidth()) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PickButton(m: Move, state: PlayState, graph: Graph, modifier: Modifier) {
    val selected = state.picked?.from == m.from && state.phase == Phase.PICKED
    val hinted = state.hintOn && state.hint?.from == m.from
    val enabled = state.phase == Phase.PICK || state.phase == Phase.PICKED
    val fg = if (selected) RoyalColors.OnPrimary else RoyalColors.Ink
    Button(
        onClick = { graph.flow.panelPick(m) }, enabled = enabled,
        modifier = modifier.then(if (selected) Modifier.border(2.dp, RoyalColors.TipWhite, RoundedCornerShape(18.dp)) else if (hinted) Modifier.border(3.dp, RoyalColors.Amber, RoundedCornerShape(18.dp)) else Modifier),
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 96.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) RoyalColors.Primary else RoyalColors.TagFace, contentColor = fg),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // design-style: inherited-content-color Button
            Txt(shortLabel(m) + if (hinted) " · " + stringResource(R.string.pick_recommended) else "", "play/pick", style = PicoTheme.typography.labelLarge, color = fg)
            RouteBar(m, selected)
        }
    }
}

/** Mini route: 16 cells (start tower, 1–14, off); your rosettes gold; a dot where the piece is, a filled cell where it lands. */
@Composable
private fun RouteBar(m: Move, onDark: Boolean) {
    val ink = if (onDark) RoyalColors.TipWhite else RoyalColors.Ink
    val target = when { m.captures -> Color(0xFFD8842A); m.rosette || m.off -> Color(0xFFE0B040); else -> Color(0xFF3F7FD9) }
    Canvas(Modifier.width(96.dp).height(10.dp)) {
        val w = size.width / 16f
        for (i in 0..15) {
            val o = Offset(i * w + 1f, 1f); val sz = Size(w - 2f, size.height - 2f)
            when {
                i == m.to -> drawRect(target, o, sz)
                i in Ur.ROSETTES -> drawRect(Color(0xFFE0B040).copy(alpha = 0.55f), o, sz)
                else -> drawRect(ink.copy(alpha = 0.18f), o, sz)
            }
        }
        drawLine(ink, Offset((m.from + 0.5f) * w, size.height / 2), Offset((m.to + 0.5f) * w, size.height / 2), strokeWidth = 2f)
        drawCircle(ink, size.height / 2.6f, Offset((m.from + 0.5f) * w, size.height / 2))
    }
}

/** Main button (56): grey "pick one first" / [走 · 后果] when only one move / [走这颗] + [取消] / grey "moving". */
@Composable
private fun MainButton(state: PlayState, graph: Graph) {
    val flow = graph.flow
    when {
        state.phase == Phase.PICK && state.legal.size == 1 ->
            PrimaryButton(stringResource(R.string.btn_move_quick, shortLabel(state.legal.single())), "play/quick", onClick = { flow.panelQuickMove() }, modifier = Modifier.fillMaxWidth())
        state.phase == Phase.PICKED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.btn_move), "play/move-btn", onClick = { flow.moveButton() }, modifier = Modifier.weight(1.4f))
            SecondaryButton(stringResource(R.string.btn_cancel), "play/cancel", onClick = { flow.cancelPick() }, modifier = Modifier.weight(1f).height(56.dp))
        }
        else -> GreyPill(stringResource(if (state.phase == Phase.MOVING) R.string.main_moving else R.string.main_choose), "play/main-grey")
    }
}

/** Not a disabled button (grey on grey was 1.9:1, D13): ink text on a light pill. */
@Composable
private fun GreyPill(text: String, tag: String) {
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(RoyalColors.TagFace), contentAlignment = Alignment.Center) {
        Txt(text, tag, style = PicoTheme.typography.titleMedium, color = RoyalColors.InkSecondary)
    }
}

@Composable
private fun SecondaryRow(state: PlayState, graph: Graph) {
    val flow = graph.flow
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.canRedo) SecondaryButton(stringResource(R.string.btn_redo), "play/redo", onClick = { flow.redo() }, icon = R.drawable.ic_redo, modifier = Modifier.weight(1f))
        else SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.requestUndo() }, enabled = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
        ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.weight(1.1f))
        SecondaryButton(stringResource(R.string.btn_more_dots), "play/more", onClick = { Ui.moreOpen = true }, modifier = Modifier.weight(0.9f))
    }
}

@Composable
private fun MoreRow(graph: Graph) {
    val flow = graph.flow
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.btn_resign), "more/resign", onClick = { Ui.moreOpen = false; flow.requestConfirm(ConfirmKind.RESIGN) }, icon = R.drawable.ic_flag, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.btn_restart), "more/restart", onClick = { Ui.moreOpen = false; flow.requestConfirm(ConfirmKind.RESTART) }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.btn_collapse), "more/close", onClick = { Ui.moreOpen = false }, modifier = Modifier.weight(0.8f))
    }
}

/** S14: a whole-page confirmation card; the volume is locked while it is open (GameFlow ignores 3D taps). */
@Composable
private fun ConfirmPage(kind: ConfirmKind?, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit, newGame: Boolean = false) {
    val (q, line, yes) = when {
        newGame -> Triple(R.string.confirm_new_game, R.string.confirm_new_line, R.string.confirm_yes_new)
        kind == ConfirmKind.RESTART -> Triple(R.string.confirm_restart, R.string.confirm_restart_line, R.string.confirm_yes_restart)
        kind == ConfirmKind.RESIGN -> Triple(R.string.confirm_resign, R.string.confirm_resign_line, R.string.confirm_yes_resign)
        kind == ConfirmKind.EXIT -> Triple(R.string.confirm_exit, R.string.confirm_exit_line, R.string.confirm_yes_exit)
        else -> Triple(R.string.confirm_undo_2p, R.string.confirm_undo_line, R.string.confirm_yes_undo)
    }
    val icon = when { newGame || kind == ConfirmKind.RESTART -> R.drawable.ic_replay; kind == ConfirmKind.RESIGN -> R.drawable.ic_flag; kind == ConfirmKind.EXIT -> R.drawable.ic_exit; else -> R.drawable.ic_undo }
    val danger = kind != ConfirmKind.UNDO_2P || newGame
    PageColumn {
        Header(stringResource(R.string.tab_play).let { if (kind == ConfirmKind.EXIT) stringResource(R.string.settings_title) else if (newGame) stringResource(R.string.new_title) else it }, "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(RoyalColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(44.dp), tint = RoyalColors.Ink)
            Txt(stringResource(q), "confirm/${kind?.name ?: "NEW"}", style = PicoTheme.typography.titleLarge.copy(fontSize = 24.sp), color = RoyalColors.Ink, maxLines = 2)
            Txt(stringResource(line), "confirm/line", style = PicoTheme.typography.bodyLarge, color = RoyalColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(if (kind == ConfirmKind.UNDO_2P && !newGame) R.string.btn_never else R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(56.dp))
                SecondaryButton(stringResource(yes), "confirm/yes", danger = danger, onClick = onYes, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S11: the result card (whole page, [静音] still in the header). */
@Composable
private fun ResultPage(state: PlayState, settings: Settings, graph: Graph) {
    val w = state.winner ?: return
    val cfg = state.config ?: return
    val title = stringResource(when {
        cfg.mode == Mode.AI && w == Ur.NEAR -> R.string.result_you_win
        cfg.mode == Mode.AI -> R.string.result_you_lose
        w == Ur.NEAR -> R.string.result_near_win
        else -> R.string.result_far_win
    })
    val me = if (cfg.mode == Mode.AI) Ur.NEAR else w
    val secs = state.elapsedMs / 1000
    val time = "%d:%02d".format(secs / 60, secs % 60)
    PageColumn {
        Header(stringResource(R.string.tab_play), "result", settings, graph, sub = modeLine(state))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(RoyalColors.TagFace).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(title, "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), color = RoyalColors.Ink)
            if (state.resigned) Txt(stringResource(R.string.result_resigned), "result/resigned", style = PicoTheme.typography.bodyMedium, color = RoyalColors.InkSecondary)
            val cells = listOf(
                R.string.res_turns to state.turnNo.toString(), R.string.res_caps to state.captures[me].toString(), R.string.res_ros to state.rosettes[me].toString(),
                R.string.res_time to time, R.string.res_caught to state.captures[Ur.other(me)].toString(),
                R.string.res_level to if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.mode_2p) else stringResource(if (cfg.aiLevel == Ai.SMART) R.string.level_smart else R.string.level_easy),
            )
            cells.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value) -> StatCell(stringResource(label), value, Modifier.weight(1f).border(1.dp, RoyalColors.InkSecondary.copy(alpha = 0.25f), RoundedCornerShape(14.dp))) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Txt(stringResource(R.string.result_footnote), "result/footnote", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    fun start() { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }
    if (Ui.newConfirm) {
        // D44: starting over drops the running game, ask first (whole-page card).
        ConfirmPage(null, settings, graph, onYes = { start() }, onNo = { Ui.newConfirm = false }, newGame = true)
        return
    }
    PageColumn {
        Header(stringResource(R.string.new_title), "new", settings, graph)
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue_n, state.savedTurn + 1), "new/continue", onClick = { graph.flow.continueSaved(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth().height(56.dp))
        }
        Group(stringResource(R.string.new_who), "new/opponent") {
            // gate:allow tabs-segment same-page option of the new game (titled group: opponent / level / who starts), not a page switch
            Segments(listOf(Mode.AI to stringResource(R.string.new_vs_ai), Mode.TWO_PLAYER to stringResource(R.string.mode_2p)), setup.mode, "new/opponent", { m -> set { it.copy(mode = m) } }, Modifier.fillMaxWidth())
        }
        if (setup.mode == Mode.AI) {
            Group(stringResource(R.string.new_how_strong), "new/level") {
                // gate:allow tabs-segment same-page option of the new game (titled group: opponent / level / who starts), not a page switch
                Segments(listOf(Ai.EASY to stringResource(R.string.level_easy), Ai.SMART to stringResource(R.string.level_smart)), setup.aiLevel, "new/level", { l -> set { it.copy(aiLevel = l) } }, Modifier.fillMaxWidth())
            }
        } else {
            Txt(stringResource(R.string.new_2p_note), "new/2p-note", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.height(76.dp).padding(top = 12.dp))
        }
        Group(stringResource(R.string.new_first2), "new/first") {
            val ai = setup.mode == Mode.AI
            // gate:allow tabs-segment same-page option of the new game (titled group: opponent / level / who starts), not a page switch
            Segments(
                listOf(
                    FirstPick.ME to stringResource(if (ai) R.string.first_me else R.string.first_near),
                    FirstPick.THEM to stringResource(if (ai) R.string.first_them else R.string.first_far),
                    FirstPick.RANDOM to stringResource(R.string.first_random),
                ),
                setup.first, "new/first", { f -> set { it.copy(first = f) } }, Modifier.fillMaxWidth(),
            )
        }
        PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
            if (state.hasProgress) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S14") } else start()
        }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.new_time), "new/time", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A titled option group: title 20 + a full-width segment 56 (same-page option switch, never a page switch). */
@Composable
private fun Group(title: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) { content() }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteDiagram()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(RoyalColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = RoyalColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodySmall, maxLines = 4, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { id ->
                    Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp)).background(RoyalColors.TagFace).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = RoyalColors.Ink, maxLines = 3)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            // [知道了] only switches to the game page (S01 never blocks the cup).
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_got_it_go else R.string.btn_back_game), "rules/got-it", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** The board from above with v1.2 tower places (yours left / right outside the board), your route and the rosettes. */
@Composable
private fun RouteDiagram() {
    Canvas(Modifier.size(width = 230.dp, height = 104.dp)) {
        val cell = size.width / 10f
        val top = (size.height - cell * 3) / 2
        fun o(r: Int, c: Int) = Offset(c * cell, top + r * cell)
        for (r in 0..2) for (c in 1..8) {
            if (r != 1 && c in 5..6) continue
            drawRoundRect(RoyalColors.Shell, o(r, c) + Offset(1.5f, 1.5f), Size(cell - 3f, cell - 3f), CornerRadius(4f, 4f))
            val ros = (r != 1 && (c == 1 || c == 7)) || (r == 1 && c == 4)
            if (ros) drawCircle(RoyalColors.Primary, cell * 0.28f, o(r, c) + Offset(cell / 2, cell / 2))
        }
        // your towers outside the board: start left (row C), finish right
        drawRoundRect(RoyalColors.Leather, o(2, 0) + Offset(4f, 4f), Size(cell - 8f, cell - 8f), CornerRadius(4f, 4f))
        drawRoundRect(RoyalColors.Leather, o(1, 9) + Offset(4f, 4f), Size(cell - 8f, cell - 8f), CornerRadius(4f, 4f))
        fun ctr(r: Int, c: Int) = o(r, c) + Offset(cell / 2, cell / 2)
        val path = listOf(ctr(2, 0), ctr(2, 4), ctr(2, 1), ctr(1, 1), ctr(1, 8), ctr(2, 8), ctr(2, 7), ctr(1, 9))
        val p = Path().apply { moveTo(path[0].x, path[0].y); path.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(p, RoyalColors.Jasper, style = Stroke(4f))
        drawCircle(RoyalColors.Jasper, 6f, path.last())
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(state: PlayState, settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(ConfirmKind.EXIT, settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Header(stringResource(R.string.settings_title), "settings", settings, graph)
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 120.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        // QA known issue 1: the system Switch's off track vanishes on glass, so every switch is an On / Off segment.
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 120.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 120.dp) {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 120.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt", labelWidth = 120.dp) {
            Segments(Settings.TILTS.map { it to "$it°" }, settings.tilt, "settings/tilt", { t -> upd { it.copy(tilt = t) } }, Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card.
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S14") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
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
