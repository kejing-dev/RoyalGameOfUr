package io.embodit.royalur.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.royalur.domain.GameFlow
import io.embodit.royalur.domain.Layout
import io.embodit.royalur.domain.Phase
import io.embodit.royalur.domain.PlayState
import io.embodit.royalur.domain.Pos
import io.embodit.royalur.domain.Settings
import io.embodit.royalur.domain.Sq
import io.embodit.royalur.domain.Tetra
import io.embodit.royalur.domain.Ur
import io.embodit.royalur.domain.V3
import io.embodit.royalur.platform.TAG
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The GLB templates (tools/build_models.py). */
internal class Models(
    val board: Entity, val pieceNear: Entity, val pieceFar: Entity, val tower: Entity, val tray: Entity, val cup: Entity, val die: Entity,
)

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves
 * (pieces, flights, the cup, the dice, rings, the rosette glow) is placed in [onFrame], driven by [BoardFrameSystem].
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped about X)
 *   │ └ boardRoot (box top centre): board.glb, 20 square colliders "sq:A1", board collider "board", 4 towers,
 *   │   14 pieces (collider children "pc:<side>:<p>" / "tw:<side>:0|15"), lit / target / last / alert rings, rosette glow
 *   ├ tray "tray", cup pivot (cup.glb + collider "cup"), cup ring, 4 dice
 *   └ seat tag, dice tag (AttachmentPanels, upright, left of the tray), key light
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var seatTag: Entity? = null
    private var diceTag: Entity? = null

    private var lay = Layout()
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    private val pieces = List(2) { ArrayList<Entity>() }
    private val pieceColliders = List(2) { ArrayList<Entity>() }
    private val colliderNames = HashMap<Entity, String>()
    private val litRings = ArrayList<ModelEntity>()
    private var selectRing: ModelEntity? = null
    private var targetRing: ModelEntity? = null
    private var towerRing: ModelEntity? = null
    private val lastRings = ArrayList<ModelEntity>()
    private var alertRing: ModelEntity? = null
    private var alertCupRing: ModelEntity? = null
    private var glowPlane: ModelEntity? = null
    private var cupRing: ModelEntity? = null
    private var cupPivot: Entity? = null
    private val dice = ArrayList<Entity>()

    private var clock = 0f
    private var glowKey: String? = null
    private var glowStart = 0L

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
    }

    val ready: Boolean get() = built

    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    /** S00 stand-in: a plain box and a cylinder for the cup, shown until the models are in. */
    fun showFallback(slab: MeshResource, cyl: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        val board = ModelEntity(slab, material).apply { setName("fallbackBoard") }
        place(board, lay.toScene(V3(0f, -Layout.BOX_T / 2, 0f)))
        board.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), lay.theta))
        val cup = ModelEntity(cyl, material).apply { setName("fallbackCup") }
        place(cup, lay.cupBase() + V3(0f, Layout.CUP_H / 2, 0f))
        fallback = listOf(board, cup)
        fallback.forEach { sceneRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun collider(name: String, shape: com.pico.spatial.core.ecs.resource.ShapeResource, r: SceneResources): Entity = Entity().apply {
        setName(name)
        components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        components.set(InteractableComponent())
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun ring(mesh: MeshResource, m: Material, name: String, parent: Entity): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; parent.addChild(this) }

    fun install(r: SceneResources, models: Models, seat: Entity?, diceTagEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(clone(models.board).apply { setName("boardModel") })
        boardRoot.addChild(collider("board", r.boardShape, r).also { place(it, V3(0f, -Layout.BOX_T / 2, 0f)) })
        for (sq in Ur.SQUARES) boardRoot.addChild(collider("sq:${sq.name}", r.squareShape, r).also { place(it, lay.square(sq) + V3(0f, 0.0015f, 0f)) })
        for (side in 0..1) for (finish in listOf(false, true)) {
            val base = clone(models.tower).apply { setName("towerBase:$side:${if (finish) 15 else 0}") }
            place(base, lay.tower(side, finish))
            base.addChild(collider("tw:$side:${if (finish) 15 else 0}", r.towerShape, r).also { place(it, V3(0f, SceneResources.TOWER_COLLIDER_H / 2, 0f)) })
            boardRoot.addChild(base)
        }
        for (side in 0..1) repeat(Ur.PIECES) { i ->
            val e = clone(if (side == Ur.NEAR) models.pieceNear else models.pieceFar).apply { setName("piece:$side:$i") }
            val c = collider("pc:$side:x", r.pieceShape, r)
            e.addChild(c)
            boardRoot.addChild(e)
            pieces[side] += e
            pieceColliders[side] += c
        }
        fun halo(parent: ModelEntity, mesh: MeshResource) = parent.addChild(ModelEntity(mesh, r.dark).apply { setName("${parent.getName()}:halo"); transform().setPosition(Vector3(0f, -0.0004f, 0f)) })
        repeat(Ur.PIECES) { i -> litRings += ring(r.ringPiece, r.cream, "litRing:$i", boardRoot).also { halo(it, r.haloPiece) } }
        selectRing = ring(r.ringSelect, r.cream, "selectRing", boardRoot).also { halo(it, r.haloSelect) }
        targetRing = ring(r.ringSquare, r.blue, "targetRing", boardRoot).also { halo(it, r.haloSquare) }
        towerRing = ring(r.ringTower, r.gold, "towerRing", boardRoot)
        repeat(2) { i -> lastRings += ring(r.ringSquare, r.faint, "lastRing:$i", boardRoot) }
        alertRing = ring(r.ringSquare, r.red, "alertRing", boardRoot)
        glowPlane = ring(r.glowPlane, r.glow, "rosetteGlow", boardRoot).also { it.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())) }

        sceneRoot.addChild(clone(models.tray).apply { setName("trayModel"); place(this, lay.trayCentre()) })
        sceneRoot.addChild(collider("tray", r.trayShape, r).also { place(it, lay.trayCentre() + V3(0f, Layout.TRAY_H / 2, 0f)) })
        val pivot = Entity().apply { setName("cupPivot") }
        place(pivot, lay.cupBase())
        pivot.addChild(clone(models.cup).apply { setName("cupModel") })
        pivot.addChild(collider("cup", r.cupShape, r).also { place(it, V3(0f, 0.04f, 0f)) })
        sceneRoot.addChild(pivot)
        cupPivot = pivot
        cupRing = ring(r.ringCup, r.cream, "cupRing", sceneRoot).also { place(it, lay.cupBase() + V3(0f, 0.002f, 0f)) }
        alertCupRing = ring(r.ringCup, r.red, "alertCupRing", sceneRoot).also { place(it, lay.cupBase() + V3(0f, 0.004f, 0f)) }
        repeat(4) { i -> dice += clone(models.die).apply { setName("die:$i"); enabled = false; sceneRoot.addChild(this) } }
        seatTag = seat?.also { sceneRoot.addChild(it) }
        diceTag = diceTagEntity?.also { sceneRoot.addChild(it) }
        buildLight()

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        layout(force = true)
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (not over-exposed).
            light.components.set(DirectionalLightComponent(Color4(1f, 0.94f, 0.86f, 1f), 0.9f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (35 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-50 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    private fun layout(force: Boolean = false) {
        if (!force && lay.tiltDeg == settings.tilt) return
        lay = Layout(settings.tilt)
        place(tiltRoot, lay.tiltOrigin)
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), lay.theta))
        place(boardRoot, lay.boardOffset)
        seatTag?.let { place(it, lay.seatCentre()) }
        diceTag?.let { place(it, lay.diceTagCentre()) }
        Log.i(TAG, "LAYOUT tilt=${lay.tiltDeg} seat=${lay.seatCentre()} diceTag=${lay.diceTagCentre()} farTop=${lay.toScene(V3(0f, 0f, -Layout.BOX_D / 2))}")
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        val changed = s != settings
        settings = s
        if (!built) return
        if (changed) layout()
        onFrame(0f)
    }

    // ---- per frame ----

    private data class Place(val at: V3, val name: String)

    private fun towerTop(side: Int, finish: Boolean, count: Int) = lay.slot(side, finish, (count - 1).coerceAtLeast(0))

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.takeIf { start >= st.freezeAt }?.let { min(e, it) } ?: e }

        // ---------------- pieces
        val anim = st.anim
        val m = anim?.move
        val e = anim?.let { elapsed(it.startedAt) } ?: 0L
        val step = if (m == null) 0L else if (reduced) anim.durationMs else (if (m.off) GameFlow.OFF_MS else GameFlow.STEP_MS)
        val capDur = if (m != null && m.captures && !reduced) GameFlow.CAPTURE_MS else 0L
        val stepping = m != null && e < step
        val capturing = m != null && m.captures && !stepping && e < step + capDur
        val base: Pos = if (m != null && stepping) anim.before else st.pos
        val selected = st.picked?.takeIf { st.phase == Phase.PICKED || st.phase == Phase.THINKING }
        for (side in 0..1) {
            val prog = base.of(side)
            val excluded = HashSet<Int>()
            if (m != null && stepping && side == m.side) excluded += m.piece
            if (capturing && side == Ur.other(m!!.side)) excluded += m.captured
            val places = ArrayList<Place>()
            val inStart = prog.indices.filter { it !in excluded && prog[it] == Ur.START }.size
            val inOff = prog.indices.filter { it !in excluded && prog[it] == Ur.OFF }.size
            for (k in 0 until inStart) {
                val lift = if (selected != null && selected.side == side && selected.from == Ur.START && k == inStart - 1) Layout.LIFT else 0f
                places += Place(lay.slot(side, false, k) + V3(0f, lift, 0f), "tw:$side:0")
            }
            for (i in prog.indices) {
                val p = prog[i]
                if (i in excluded || p == Ur.START || p == Ur.OFF) continue
                val lift = if (selected != null && selected.side == side && selected.from == p) Layout.LIFT else 0f
                places += Place(lay.pieceOn(side, p, lift), "pc:$side:$p")
            }
            for (k in 0 until inOff) places += Place(lay.slot(side, true, k), "tw:$side:15")
            if (m != null && stepping && side == m.side) {
                val from = if (m.from == Ur.START) towerTop(side, false, anim.before.inStart(side)) else lay.pieceOn(side, m.from)
                val to = if (m.off) lay.slot(side, true, anim.before.off(side)) else lay.pieceOn(side, m.to)
                val t = if (reduced) 1f else (e.toFloat() / step).coerceIn(0f, 1f)
                places += Place(lay.arc(from, to, t, Layout.STEP_ARC), "fly")
            }
            if (capturing && side == Ur.other(m!!.side)) {
                val from = lay.pieceOn(side, m.to)
                val to = towerTop(side, false, st.pos.inStart(side))
                val t = ((e - step).toFloat() / capDur).coerceIn(0f, 1f)
                places += Place(lay.arc(from, to, t, Layout.CAPTURE_ARC), "fly")
            }
            pieces[side].forEachIndexed { i, ent ->
                val pl = places.getOrNull(i)
                if (pl == null) { ent.enabled = false; return@forEachIndexed }
                ent.enabled = true
                place(ent, pl.at)
                val c = pieceColliders[side][i]
                if (colliderNames[c] != pl.name) { c.setName(pl.name); colliderNames[c] = pl.name }
            }
        }

        // ---------------- lit rings (S05 / S06), AI's pick (S10), hint, target
        val litFroms: List<Pair<io.embodit.royalur.domain.Move, Boolean>> = when {
            st.humansTurn && (st.phase == Phase.PICK || st.phase == Phase.PICKED) -> st.legal.map { it to (st.hint?.from == it.from) }
            else -> emptyList()
        }
        fun pieceLocal(side: Int, p: Int, lift: Float): V3 = if (p == Ur.START) towerTop(side, false, st.pos.inStart(side)) + V3(0f, lift, 0f) else lay.pieceOn(side, p, lift)
        litRings.forEachIndexed { i, ring ->
            val (mv, hinted) = litFroms.getOrNull(i) ?: run { ring.enabled = false; return@forEachIndexed }
            if (selected != null && selected.from == mv.from) { ring.enabled = false; return@forEachIndexed }
            val at = pieceLocal(mv.side, mv.from, 0f) - V3(0f, Layout.PIECE_H / 2 - 0.0012f, 0f)
            place(ring, at)
            ring.components[ModelComponent::class.java]?.materials?.set(0, when { hinted -> r.amber; st.phase == Phase.PICKED -> r.faint; else -> r.cream })
            val k = if (reduced || st.phase == Phase.PICKED) 1f else 1f + 0.05f * sin(clock * 4f)
            ring.transform().setScaleVector(Vector3(k, 1f, k))
            ring.enabled = true
        }
        selectRing?.let { ring ->
            if (selected != null) {
                place(ring, pieceLocal(selected.side, selected.from, Layout.LIFT) - V3(0f, Layout.PIECE_H / 2 - 0.0012f, 0f))
                ring.components[ModelComponent::class.java]?.materials?.set(0, if (st.hint?.from == selected.from) r.amber else r.cream)
                ring.enabled = true
            } else ring.enabled = false
        }
        val target = st.picked?.takeIf { st.phase == Phase.PICKED && st.humansTurn }
        targetRing?.let { ring ->
            if (target != null && !target.off) {
                place(ring, lay.square(Ur.square(target.side, target.to)) + V3(0f, 0.0012f, 0f))
                ring.components[ModelComponent::class.java]?.materials?.set(0, when { target.captures -> r.orange; target.rosette -> r.gold; else -> r.blue })
                val k = if (reduced) 1f else 1f + 0.06f * sin(clock * 6f)
                ring.transform().setScaleVector(Vector3(k, 1f, k))
                ring.enabled = true
            } else ring.enabled = false
        }
        towerRing?.let { ring ->
            if (target != null && target.off) {
                place(ring, lay.slot(target.side, true, st.pos.off(target.side)) - V3(0f, Layout.PIECE_H / 2 - 0.001f, 0f))
                ring.enabled = true
            } else ring.enabled = false
        }
        // last move: faint blue rings on its from / to squares
        val last = st.last?.takeIf { st.phase != Phase.PICKED && st.anim == null && st.phase != Phase.RESULT }
        val lastSquares = listOfNotNull(last?.from?.takeIf { it in 1..14 }, last?.to?.takeIf { it in 1..14 }).map { Ur.square(last!!.side, it) }
        lastRings.forEachIndexed { i, ring ->
            val sq = lastSquares.getOrNull(i)
            if (sq != null) { place(ring, lay.square(sq) + V3(0f, 0.0008f, 0f)); ring.enabled = true } else ring.enabled = false
        }

        // ---------------- alert flash (S15)
        val al = st.alert
        val flashing = al != null && st.freezeMs == null && now - al.at < 600 && ((now - al.at) / 100) % 2 == 0L ||
            (al != null && st.freezeMs != null)
        alertRing?.let { ring ->
            val at = al?.target?.let { alertLocal(it) }
            if (flashing && at != null) { place(ring, at); ring.enabled = true } else ring.enabled = false
        }
        alertCupRing?.enabled = flashing && al?.target == "cup"

        // ---------------- rosette glow (S07 landing, S08)
        val glowSq: Sq? = when {
            m != null && m.rosette && !m.off && !stepping -> Ur.square(m.side, m.to)
            st.phase == Phase.AGAIN && st.last?.rosette == true -> Ur.square(st.last.side, st.last.to)
            else -> null
        }
        glowPlane?.let { g ->
            if (glowSq != null) {
                val key = "${glowSq.name}:${st.turnNo}:${st.rosettes}"
                if (key != glowKey) { glowKey = key; glowStart = now }
                val k = if (reduced) 1f else (elapsed(glowStart).toFloat() / 400f).coerceIn(0.05f, 1f)
                place(g, lay.square(glowSq) + V3(0f, 0.0009f, 0f))
                g.transform().setScaleVector(Vector3(k, k, 1f))
                g.enabled = true
            } else { g.enabled = false; glowKey = null }
        }

        // ---------------- cup: its ring at S03 / S08, its tilt while throwing (S04)
        cupRing?.let { ring ->
            val on = st.humansTurn && st.confirm == null && (st.phase == Phase.TO_ROLL || st.phase == Phase.AGAIN)
            ring.enabled = on
            if (on) { val k = if (reduced) 1f else 1f + 0.06f * sin(clock * 3.5f); ring.transform().setScaleVector(Vector3(k, 1f, k)) }
        }
        val show = st.dice
        val de = show?.let { elapsed(it.startedAt) } ?: Long.MAX_VALUE
        cupPivot?.let { pivot ->
            val total = GameFlow.ROLL_MS
            val (lift, ang) = if (show == null || reduced || de >= total) 0f to 0f else {
                val u = when {
                    de < 200 -> de / 200f
                    de < 600 -> 1f
                    else -> 1f - (de - 600) / 300f
                }.coerceIn(0f, 1f)
                (0.03f * u) to (70f * u)
            }
            place(pivot, lay.cupBase() + V3(0f, lift, 0f))
            pivot.transform().setQuaternion(Quat(Vector3(0f, 0f, 1f), Math.toRadians(ang.toDouble()).toFloat()))
        }

        // ---------------- dice
        dice.forEachIndexed { i, d ->
            if (show == null) { d.enabled = false; return@forEachIndexed }
            val thr = show.throws.getOrNull(i) ?: run { d.enabled = false; return@forEachIndexed }
            val lane = Layout.LANES[i] + V3(0f, Layout.TRAY_FLOOR, 0f)
            var scale = 1f
            val pose: Pair<V3, DoubleArray>? = if (reduced) {
                scale = (de / 200f).coerceIn(0.1f, 1f)
                val p = thr.rest
                V3(lane.x + p.position[0].toFloat(), lane.y + p.position[1].toFloat(), lane.z + p.position[2].toFloat()) to p.quaternion
            } else {
                val rel = 200L + 60L * i
                if (de < rel) null
                else {
                    val avail = (560 - 60 * i).coerceAtLeast(200)
                    val speed = maxOf(1.0, thr.duration * 1000 / avail)
                    val f0 = thr.frames.first()
                    val start = V3(lane.x + f0.position[0].toFloat(), lane.y + f0.position[1].toFloat(), lane.z + f0.position[2].toFloat())
                    if (de < rel + 120) {
                        val mouth = lay.cupBase() + V3(-0.045f, 0.06f, 0f)
                        val u = (de - rel) / 120f
                        mouth.lerp(start, u) to f0.quaternion
                    } else {
                        val p = thr.sample((de - rel - 120) * speed / 1000.0)
                        V3(lane.x + p.position[0].toFloat(), lane.y + p.position[1].toFloat(), lane.z + p.position[2].toFloat()) to p.quaternion
                    }
                }
            }
            show.fadeAt?.let { f -> scale *= 1f - (elapsed(f).toFloat() / GameFlow.DICE_FADE_MS) }
            if (pose == null || scale <= 0.02f) { d.enabled = false; return@forEachIndexed }
            place(d, pose.first)
            val q = pose.second
            d.transform().setQuaternion(Quat(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()))
            d.transform().setScaleVector(Vector3(scale, scale, scale))
            d.enabled = true
        }
    }

    /** Board-local point where the red alert ring goes for an alert target tag. */
    private fun alertLocal(target: String): V3? {
        val f = target.split(":")
        return when (f[0]) {
            "sq" -> Sq.parse(f[1])?.let { lay.square(it) + V3(0f, 0.0012f, 0f) }
            "tw" -> {
                val side = f[1].toInt(); val finish = f[2] == "15"
                val n = if (finish) state.pos.off(side) else state.pos.inStart(side)
                lay.slot(side, finish, (n - 1).coerceAtLeast(0)) + V3(0f, Layout.PIECE_H / 2 + 0.001f, 0f)
            }
            else -> null
        }
    }

    fun evidence(): String {
        val dz = dice.mapIndexed { i, d -> "d$i=${if (d.enabled) d.transform().position else "off"}" }.joinToString(" ")
        return "tilt=${lay.tiltDeg} seat=${lay.seatCentre()} diceTag=${lay.diceTagCentre()} cup=${lay.cupBase()} " +
            "C1=${lay.toScene(lay.square(Sq(2, 1)))} A8=${lay.toScene(lay.square(Sq(0, 8)))} halfY=$regionHalfY $dz restH=${Tetra.REST_HEIGHT_M}"
    }

    fun dispose() {
        res?.close()
        res = null
    }

    companion object {
        /** A collider name -> what was tapped. */
        sealed interface Hit
        data class PieceHit(val side: Int, val p: Int) : Hit
        data class SquareHit(val sq: Sq) : Hit
        data object CupHit : Hit
        data object TrayHit : Hit
        data object EmptyHit : Hit

        fun hitOf(name: String?): Hit {
            if (name == null) return EmptyHit
            val f = name.split(":")
            return when (f[0]) {
                "pc" -> f.getOrNull(2)?.toIntOrNull()?.let { PieceHit(f[1].toInt(), it) } ?: EmptyHit
                "tw" -> PieceHit(f[1].toInt(), f[2].toInt())
                "sq" -> Sq.parse(f[1])?.let { SquareHit(it) } ?: EmptyHit
                "cup" -> CupHit
                "tray" -> TrayHit
                else -> EmptyHit
            }
        }
    }
}
