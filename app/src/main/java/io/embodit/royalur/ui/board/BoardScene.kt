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
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.royalur.domain.GameFlow
import io.embodit.royalur.domain.Layout
import io.embodit.royalur.domain.Move
import io.embodit.royalur.domain.Phase
import io.embodit.royalur.domain.PlayState
import io.embodit.royalur.domain.Pos
import io.embodit.royalur.domain.Settings
import io.embodit.royalur.domain.Spot
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
 * Every entity of the board volume (design v1.2). Compose hands over [PlayState] + [Settings] through [apply]; everything
 * that moves (pieces, flights, the cup, the dice, rings, the rosette glow) is placed in [onFrame], driven by
 * [BoardFrameSystem].
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped about X)
 *   │ └ boardRoot (box top centre): board.glb, 20 square colliders "sq:A1", board collider "board", the far side's 2 towers
 *   ├ your 2 towers (beside the board, flat), tray, cup pivot (cup.glb + collider "cup"), 4 dice
 *   ├ 14 pieces (collider children "pc:<side>:<p>" / "tw:<side>:0|15") and all rings: scene children, tipped with the
 *   │   board when they stand on it
 *   └ seat tag, dice tag (AttachmentPanels in the low zone in front), key light
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
    private var tiltQ = Quat(Vector3(1f, 0f, 0f), lay.theta)
    private val flatQ = Quat.identity()
    private val planeQ = Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    private val pieces = List(2) { ArrayList<Entity>() }
    private val pieceColliders = List(2) { ArrayList<Entity>() }
    private val colliderNames = HashMap<Entity, String>()
    private val nearTowers = ArrayList<Entity>()
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

    private var hoverRing: ModelEntity? = null
    @Volatile private var hover: String? = null

    /** D50 hover: the collider name under a resting pointer (null = none). Light feedback only. */
    fun setHover(name: String?) {
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    /** The collider name of a piece entity (its collider child), for hits on the piece's GLB meshes. */
    fun colliderNameOf(e: Entity): String? {
        for (side in 0..1) pieces[side].forEachIndexed { i, p -> if (p === e) return colliderNames[pieceColliders[side][i]] }
        nearTowers.forEach { t -> if (t === e) return t.getName().replace("towerBase", "tw") }
        return null
    }

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

    /** Puts a scene child at a spot, tipped with the board when the spot is on it. */
    private fun place(e: Entity, s: Spot, extra: Quat? = null) {
        place(e, s.at)
        val q = if (s.tilted) tiltQ else flatQ
        e.transform().setQuaternion(if (extra != null) q.times(extra) else q)
    }

    /** Lifts a spot by [dy] along its own up (the board normal when tilted). */
    private fun up(s: Spot, dy: Float): Spot = if (!s.tilted) Spot(s.at + V3(0f, dy, 0f), false) else Spot(lay.toScene(lay.toLocal(s.at) + V3(0f, dy, 0f)), true)

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

    private fun collider(name: String, shape: ShapeResource, r: SceneResources): Entity = Entity().apply {
        setName(name)
        components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        components.set(InteractableComponent())
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    /** q1-2: a coloured unlit ring on top and a dark under-ring only 1 mm wider each side, 0.6 mm lower. */
    private fun ring(mesh: MeshResource, m: Material, name: String, halo: MeshResource?, r: SceneResources): ModelEntity {
        val e = ModelEntity(mesh, m).apply { setName(name); enabled = false }
        if (halo != null) e.addChild(ModelEntity(halo, r.dark).apply { setName("$name:halo"); transform().setPosition(Vector3(0f, -0.0006f, 0f)); transform().setScaleVector(Vector3(1f, 0.15f, 1f)) })
        sceneRoot.addChild(e)
        return e
    }

    fun install(r: SceneResources, models: Models, seat: Entity?, diceTagEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(clone(models.board).apply { setName("boardModel") })
        boardRoot.addChild(collider("board", r.boardShape, r).also { place(it, V3(0f, -Layout.BOX_T / 2, 0f)) })
        for (sq in Ur.SQUARES) boardRoot.addChild(collider("sq:${sq.name}", r.squareShape, r).also { place(it, lay.square(sq) + V3(0f, 0.0015f, 0f)) })
        // the far side's towers in the A-row gaps (tip with the board)
        for (finish in listOf(false, true)) {
            val base = clone(models.tower).apply { setName("towerBase:1:${if (finish) 15 else 0}") }
            place(base, lay.farTower(finish))
            base.addChild(collider("tw:1:${if (finish) 15 else 0}", r.towerShape, r).also { place(it, V3(0f, SceneResources.TOWER_COLLIDER_H / 2, 0f)) })
            boardRoot.addChild(base)
        }
        // your towers beside the board (q1-1: nothing tall in front of or inside the near row)
        for (finish in listOf(false, true)) {
            val base = clone(models.tower).apply { setName("towerBase:0:${if (finish) 15 else 0}") }
            place(base, if (finish) Layout.NEAR_FINISH else Layout.NEAR_START)
            base.addChild(collider("tw:0:${if (finish) 15 else 0}", r.towerShape, r).also { place(it, V3(0f, SceneResources.TOWER_COLLIDER_H / 2, 0f)) })
            sceneRoot.addChild(base)
            nearTowers += base
        }
        for (side in 0..1) repeat(Ur.PIECES) { i ->
            val e = clone(if (side == Ur.NEAR) models.pieceNear else models.pieceFar).apply { setName("piece:$side:$i") }
            val c = collider("pc:$side:x", r.pieceShape, r)
            e.addChild(c)
            sceneRoot.addChild(e)
            pieces[side] += e
            pieceColliders[side] += c
        }
        repeat(Ur.PIECES) { i -> litRings += ring(r.ringPiece, r.cream, "litRing:$i", r.haloPiece, r) }
        selectRing = ring(r.ringSelect, r.cream, "selectRing", r.haloSelect, r)
        targetRing = ring(r.ringSquare, r.blue, "targetRing", r.haloSquare, r)
        towerRing = ring(r.ringTower, r.gold, "towerRing", r.haloTower, r)
        repeat(2) { i -> lastRings += ring(r.ringSquare, r.faint, "lastRing:$i", null, r) }
        alertRing = ring(r.ringSquare, r.red, "alertRing", r.haloSquare, r)
        glowPlane = ring(r.glowPlane, r.glow, "rosetteGlow", null, r)
        hoverRing = ring(r.ringSquare, r.creamFaint, "hoverRing", null, r)

        sceneRoot.addChild(clone(models.tray).apply { setName("trayModel"); place(this, lay.trayCentre()) })
        sceneRoot.addChild(collider("tray", r.trayShape, r).also { place(it, lay.trayCentre() + V3(0f, Layout.TRAY_H / 2, 0f)) })
        val pivot = Entity().apply { setName("cupPivot") }
        place(pivot, lay.cupBase())
        pivot.addChild(clone(models.cup).apply { setName("cupModel") })
        pivot.addChild(collider("cup", r.cupShape, r).also { place(it, V3(0f, 0.04f, 0f)) })
        sceneRoot.addChild(pivot)
        cupPivot = pivot
        cupRing = ModelEntity(r.ringCup, r.cream).apply { setName("cupRing"); enabled = false; place(this, lay.cupBase() + V3(0f, 0.002f, 0f)); sceneRoot.addChild(this) }
        alertCupRing = ModelEntity(r.ringCup, r.red).apply { setName("alertCupRing"); enabled = false; place(this, lay.cupBase() + V3(0f, 0.004f, 0f)); sceneRoot.addChild(this) }
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
        tiltQ = Quat(Vector3(1f, 0f, 0f), lay.theta)
        place(tiltRoot, lay.tiltOrigin)
        tiltRoot.transform().setQuaternion(tiltQ)
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

    private data class Place(val at: Spot, val name: String)

    private fun towerTop(side: Int, finish: Boolean, count: Int): Spot = lay.slot(side, finish, (count - 1).coerceAtLeast(0))

    /** Where the piece of [side] at progress [p] stands right now (top of a tower for 0 / 15), lifted by [lift]. */
    private fun pieceSpot(side: Int, p: Int, pos: Pos, lift: Float = 0f): Spot = when (p) {
        Ur.START -> lay.slot(side, false, (pos.inStart(side) - 1).coerceAtLeast(0), lift)
        Ur.OFF -> lay.slot(side, true, (pos.off(side) - 1).coerceAtLeast(0), lift)
        else -> lay.onBoard(side, p, lift)
    }

    /** A ring around a piece: its base plus [dy] (colour 1.8 mm, the halo rides 0.6 mm under it). */
    private fun ringAround(s: Spot, dy: Float = 0.0018f): Spot = up(s, -Layout.PIECE_H / 2 + dy)

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
                places += Place(lay.slot(side, false, k, lift), "tw:$side:0")
            }
            for (i in prog.indices) {
                val p = prog[i]
                if (i in excluded || p == Ur.START || p == Ur.OFF) continue
                val lift = if (selected != null && selected.side == side && selected.from == p) Layout.LIFT else 0f
                places += Place(lay.onBoard(side, p, lift), "pc:$side:$p")
            }
            for (k in 0 until inOff) places += Place(lay.slot(side, true, k), "tw:$side:15")
            if (m != null && stepping && side == m.side) {
                val from = if (m.from == Ur.START) towerTop(side, false, anim.before.inStart(side)) else lay.onBoard(side, m.from)
                val to = if (m.off) lay.slot(side, true, anim.before.off(side)) else lay.onBoard(side, m.to)
                val t = if (reduced) 1f else (e.toFloat() / step).coerceIn(0f, 1f)
                places += Place(Spot(lay.arc(from.at, to.at, t, Layout.STEP_ARC), if (t < 0.5f) from.tilted else to.tilted), "fly")
            }
            if (capturing && side == Ur.other(m!!.side)) {
                val from = lay.onBoard(side, m.to)
                val to = towerTop(side, false, st.pos.inStart(side))
                val t = ((e - step).toFloat() / capDur).coerceIn(0f, 1f)
                places += Place(Spot(lay.arc(from.at, to.at, t, Layout.CAPTURE_ARC), if (t < 0.5f) from.tilted else to.tilted), "fly")
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

        // ---------------- lit rings (S05 / S06), the AI's pick (S10), hint, target
        val lit: List<Pair<Move, Boolean>> = if (st.humansTurn && (st.phase == Phase.PICK || st.phase == Phase.PICKED)) st.legal.map { it to (st.hint?.from == it.from) } else emptyList()
        litRings.forEachIndexed { i, ring ->
            val (mv, hinted) = lit.getOrNull(i) ?: run { ring.enabled = false; return@forEachIndexed }
            if (selected != null && selected.from == mv.from) { ring.enabled = false; return@forEachIndexed }
            place(ring, ringAround(pieceSpot(mv.side, mv.from, st.pos)))
            ring.components[ModelComponent::class.java]?.materials?.set(0, when { hinted -> r.amber; st.phase == Phase.PICKED -> r.creamFaint; else -> r.cream })
            val k = if (reduced || st.phase == Phase.PICKED) 1f else 1f + 0.04f * sin(clock * 4f)
            ring.transform().setScaleVector(Vector3(k, 1f, k))
            ring.enabled = true
        }
        selectRing?.let { ring ->
            if (selected != null) {
                place(ring, ringAround(pieceSpot(selected.side, selected.from, st.pos, Layout.LIFT)))
                ring.components[ModelComponent::class.java]?.materials?.set(0, if (st.hint?.from == selected.from) r.amber else r.cream)
                ring.enabled = true
            } else ring.enabled = false
        }
        val target = st.picked?.takeIf { st.phase == Phase.PICKED && st.humansTurn }
        targetRing?.let { ring ->
            if (target != null && !target.off) {
                place(ring, lay.squareSpot(Ur.square(target.side, target.to), 0.0018f))
                ring.components[ModelComponent::class.java]?.materials?.set(0, when { target.captures -> r.orange; target.rosette -> r.gold; else -> r.blue })
                val k = if (reduced) 1f else 1f + 0.05f * sin(clock * 6f)
                ring.transform().setScaleVector(Vector3(k, 1f, k))
                ring.enabled = true
            } else ring.enabled = false
        }
        towerRing?.let { ring ->
            if (target != null && target.off) {
                place(ring, ringAround(lay.slot(target.side, true, st.pos.off(target.side))))
                ring.enabled = true
            } else ring.enabled = false
        }
        // last move: faint blue rings on its from / to squares
        val last = st.last?.takeIf { st.phase != Phase.PICKED && st.anim == null && st.phase != Phase.RESULT }
        val lastSquares = listOfNotNull(last?.from?.takeIf { it in 1..14 }, last?.to?.takeIf { it in 1..14 }).map { Ur.square(last!!.side, it) }
        lastRings.forEachIndexed { i, ring ->
            val sq = lastSquares.getOrNull(i)
            if (sq != null) { place(ring, lay.squareSpot(sq, 0.0012f)); ring.enabled = true } else ring.enabled = false
        }

        // ---------------- hover (D50): a faint cream ring where the pointer rests; never changes state
        hoverRing?.let { ring ->
            val at = hover?.let { hoverSpot(it) }
            if (at != null) { place(ring, at); ring.enabled = true } else ring.enabled = false
        }

        // ---------------- alert flash (S15)
        val al = st.alert
        val flashing = al != null && st.freezeMs == null && now - al.at < 600 && ((now - al.at) / 100) % 2 == 0L ||
            (al != null && st.freezeMs != null)
        alertRing?.let { ring ->
            val at = al?.target?.let { alertSpot(it) }
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
                place(g, lay.squareSpot(glowSq, 0.0009f), planeQ)
                g.transform().setScaleVector(Vector3(k, k, 1f))
                g.enabled = true
            } else { g.enabled = false; glowKey = null }
        }

        // ---------------- cup: its ring at S03 / S08, the pour while throwing (S04)
        cupRing?.let { ring ->
            val on = st.humansTurn && st.confirm == null && (st.phase == Phase.TO_ROLL || st.phase == Phase.AGAIN)
            ring.enabled = on
            if (on) { val k = if (reduced) 1f else 1f + 0.06f * sin(clock * 3.5f); ring.transform().setScaleVector(Vector3(k, 1f, k)) }
        }
        val show = st.dice
        val de = show?.let { elapsed(it.startedAt) } ?: Long.MAX_VALUE
        cupPivot?.let { pivot ->
            val total = GameFlow.ROLL_MS
            // lift 3 cm and slide over the tray's right end in 200 ms, tip left 70°, back home by 900 ms
            val u = if (show == null || reduced || de >= total) 0f else when {
                de < 200 -> de / 200f
                de < 600 -> 1f
                else -> 1f - (de - 600) / 300f
            }.coerceIn(0f, 1f)
            place(pivot, lay.cupBase().lerp(Layout.CUP_POUR, u) + V3(0f, 0.03f * u, 0f))
            pivot.transform().setQuaternion(Quat(Vector3(0f, 0f, 1f), Math.toRadians((70f * u).toDouble()).toFloat()))
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
                        val mouth = Layout.CUP_POUR + V3(-0.045f, 0.09f, 0f)
                        mouth.lerp(start, (de - rel) / 120f) to f0.quaternion
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

    private fun hoverSpot(name: String): Spot? {
        val f = name.split(":")
        return when (f[0]) {
            "sq" -> Sq.parse(f[1])?.let { lay.squareSpot(it, 0.0010f) }
            "pc" -> f.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..14 }?.let { ringAround(lay.onBoard(f[1].toInt(), it), 0.0010f) }
            "tw" -> alertSpot(name)
            else -> null
        }
    }

    /** Where the red alert ring goes for an alert target tag. */
    private fun alertSpot(target: String): Spot? {
        val f = target.split(":")
        return when (f[0]) {
            "sq" -> Sq.parse(f[1])?.let { lay.squareSpot(it, 0.0018f) }
            "tw" -> {
                val side = f[1].toInt(); val finish = f[2] == "15"
                val n = if (finish) state.pos.off(side) else state.pos.inStart(side)
                ringAround(lay.slot(side, finish, (n - 1).coerceAtLeast(0)))
            }
            else -> null
        }
    }

    fun evidence(): String {
        val dz = dice.mapIndexed { i, d -> "d$i=${if (d.enabled) d.transform().position else "off"}" }.joinToString(" ")
        return "tilt=${lay.tiltDeg} seat=${lay.seatCentre()} diceTag=${lay.diceTagCentre()} cup=${lay.cupBase()} nearStart=${Layout.NEAR_START} " +
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
