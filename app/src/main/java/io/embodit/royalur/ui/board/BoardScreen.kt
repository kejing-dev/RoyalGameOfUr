package io.embodit.royalur.ui.board

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.royalur.ui.board.input.BoardInputConfig
import io.embodit.royalur.ui.board.input.BoardInputListener
import io.embodit.royalur.ui.board.input.InputSource
import io.embodit.royalur.ui.board.input.TargetResolver
import io.embodit.royalur.ui.board.input.boardInput
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.royalur.R
import io.embodit.royalur.domain.Layout
import io.embodit.royalur.domain.Mode
import io.embodit.royalur.domain.Phase
import io.embodit.royalur.domain.PlayState
import io.embodit.royalur.domain.Ur
import io.embodit.royalur.platform.Graph
import io.embodit.royalur.platform.TAG
import io.embodit.royalur.ui.theme.RoyalColors
import io.embodit.royalur.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

private const val SEAT = "seatTag"
private const val DICE = "diceTag"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board,
 * and routes every pinch / trigger on a collider (cup, piece, tower, square, tray, board) to [GameFlow].
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // D50: the factory board-input template (taps + short drags that the system reports as drags, targets
            // resolved up the parent chain) for hand ray, controller ray and eye-hand alike.
            // hover (D50): the template's onHover lights a faint ring on the square / piece under the pointer
            .boardInput(remember(scene) { BoardTargets(scene) }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(logTag = TAG)),
        attachments = {
            AttachmentPanel(SEAT) { TagRoot(graph) { st -> SeatTag(st, graph) } }
            AttachmentPanel(DICE) { TagRoot(graph) { st -> DiceTag(st) } }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底 深色盒"), removed when the GLBs are in
                    val slab = MeshResource.createBox(Vector3(Layout.BOX_W, Layout.BOX_T, Layout.BOX_D), cornerRadius = 0.004f)
                    // gate:allow geometry S00 loading stand-in for the cup (design lifecycle "一个圆锥台")
                    val cyl = MeshResource.createCylinder(radius = Layout.CUP_R, height = Layout.CUP_H)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.30f, 0.22f, 0.15f, 1f)); setRoughness(0.8f) }
                    Triple(slab, cyl, mat)
                }.getOrNull()
            }
            quick?.let { (s, c, m) -> scene.showFallback(s, c, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                Models(load("board"), load("piece_near"), load("piece_far"), load("tower"), load("tray"), load("cup"), load("die"))
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            if (models != null) scene.install(resources, models, attachments.entity(SEAT), attachments.entity(DICE))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Hit entity (or an ancestor) -> the collider name the game knows: squares, pieces (via their collider child), towers, cup, tray, board. */
internal class BoardTargets(private val scene: BoardScene) : TargetResolver<String> {
    override fun resolve(entity: Entity): String? {
        val name = runCatching { entity.getName() }.getOrNull() ?: return null
        if (BoardScene.hitOf(name) != BoardScene.Companion.EmptyHit) return name
        scene.colliderNameOf(entity)?.let { return it }
        return if (name == "board" || name == "boardModel") "board" else null
    }

    fun resolveName(entity: Entity?): String? = io.embodit.royalur.ui.board.input.resolveUp(entity, this).first
}

/** Every resolved tap goes through [route] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        Log.i(TAG, "PINCH target=$target src=${source.label}")
        route(graph, target)
    }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Collider name -> the one flow call (the same calls the debug broadcast makes). */
internal fun route(graph: Graph, name: String?) {
    val flow = graph.flow
    when (val h = BoardScene.hitOf(name)) {
        is BoardScene.Companion.PieceHit -> flow.tapPiece(h.side, h.p)
        is BoardScene.Companion.SquareHit -> flow.tapSquare(h.sq)
        BoardScene.Companion.CupHit -> flow.tapCup()
        BoardScene.Companion.TrayHit -> flow.tapTray()
        BoardScene.Companion.EmptyHit -> flow.tapEmpty()
    }
}

/** A tag is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun TagRoot(graph: Graph, content: @Composable (PlayState) -> Unit) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { content(st) }
    }
}

@Composable
private fun tagText(text: String, tag: String, size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = RoyalColors.Ink) {
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/** Seat tag (design panels.seatTag): whose turn + the seat colour; a first-run line; never a button except "open panel". */
@Composable
private fun SeatTag(st: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        Modifier.size(Layout.SEAT_W_DP.dp, Layout.SEAT_H_DP.dp).clip(RoundedCornerShape(10.dp)).background(RoyalColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> tagText(stringResource(R.string.level_hint), "seat/level", 11)
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("tag") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 11.sp)
            }
            else -> {
                val cfg = st.config
                val side = st.side
                val text = when {
                    cfg == null || st.phase == Phase.SETUP || st.phase == Phase.LOADING -> stringResource(R.string.seat_ready)
                    st.winner != null -> stringResource(if (cfg.mode == Mode.AI) (if (st.winner == Ur.NEAR) R.string.seat_you_won else R.string.seat_they_won) else (if (st.winner == Ur.NEAR) R.string.seat_near_won else R.string.seat_far_won))
                    cfg.mode == Mode.TWO_PLAYER -> stringResource(if (side == Ur.NEAR) R.string.seat_near else R.string.seat_far)
                    side == Ur.NEAR -> stringResource(R.string.seat_you)
                    else -> stringResource(R.string.seat_them)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (cfg != null && st.winner == null && st.phase != Phase.SETUP) {
                            Box(Modifier.size(11.dp).clip(RoundedCornerShape(6.dp)).background(if (side == Ur.NEAR) RoyalColors.Obsidian else RoyalColors.Jasper).border(1.dp, RoyalColors.Ink, RoundedCornerShape(6.dp)))
                        }
                        tagText(text, "seat/turn", 16)
                    }
                    if (st.welcome || st.firstGame && st.turnNo < 2) tagText(stringResource(R.string.seat_panel_right), "seat/panel", 9, FontWeight.Normal, RoyalColors.InkSecondary)
                }
            }
        }
    }
}

/** Dice tag (design panels.diceTag): the committed roll 0-4 and four small tips; empty between turns (D38: same source as the panel). */
@Composable
private fun DiceTag(st: PlayState) {
    val roll = st.roll?.takeIf { st.phase != Phase.ROLLING }
    Box(
        Modifier.size(Layout.DICE_W_DP.dp, Layout.DICE_H_DP.dp).clip(RoundedCornerShape(10.dp)).background(RoyalColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        if (roll != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tagText(roll.value.toString(), "dice/value", 25, FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { Tip(roll.dice[0]); Tip(roll.dice[1]) }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { Tip(roll.dice[2]); Tip(roll.dice[3]) }
                }
            }
        }
    }
}

/** A small die tip: white = counts, black = does not. */
@Composable
internal fun Tip(white: Boolean, size: Int = 10) {
    Canvas(Modifier.size(size.dp)) {
        val p = Path().apply { moveTo(this@Canvas.size.width / 2, 0f); lineTo(this@Canvas.size.width, this@Canvas.size.height); lineTo(0f, this@Canvas.size.height); close() }
        drawPath(p, RoyalColors.Bitumen)
        if (white) {
            val q = Path().apply {
                moveTo(this@Canvas.size.width / 2, 0f); lineTo(this@Canvas.size.width * 0.72f, this@Canvas.size.height * 0.45f); lineTo(this@Canvas.size.width * 0.28f, this@Canvas.size.height * 0.45f); close()
            }
            drawPath(q, RoyalColors.TipWhite)
        } else {
            drawPath(Path().apply { moveTo(this@Canvas.size.width / 2, this@Canvas.size.height * 0.08f); lineTo(this@Canvas.size.width * 0.66f, this@Canvas.size.height * 0.38f); lineTo(this@Canvas.size.width * 0.34f, this@Canvas.size.height * 0.38f); close() }, Color(0xFF55504A))
        }
    }
}

