package io.embodit.royalur.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.royalur.domain.Layout
import io.embodit.royalur.ui.board.input.GazeTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The marks of the volume (rings, the rosette glow) and the invisible collider shapes. The visible models (board,
 * pieces, towers, tray, cup, dice) are GLBs built by tools/build_models.py; only abstract marks are SDK meshes.
 * Creating a resource waits on the renderer, so everything is created concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var ringPiece: MeshResource; private set
    lateinit var ringSelect: MeshResource; private set
    lateinit var ringSquare: MeshResource; private set
    lateinit var ringCup: MeshResource; private set
    lateinit var ringTower: MeshResource; private set
    lateinit var glowPlane: MeshResource; private set
    lateinit var haloPiece: MeshResource; private set
    lateinit var haloSelect: MeshResource; private set
    lateinit var haloSquare: MeshResource; private set
    lateinit var haloTower: MeshResource; private set
    lateinit var dark: Material; private set
    lateinit var creamFaint: Material; private set

    lateinit var cream: Material; private set
    lateinit var blue: Material; private set
    lateinit var orange: Material; private set
    lateinit var gold: Material; private set
    lateinit var amber: Material; private set
    lateinit var red: Material; private set
    lateinit var faint: Material; private set
    lateinit var glow: Material; private set

    lateinit var squareShape: ShapeResource; private set
    lateinit var pieceShape: ShapeResource; private set
    lateinit var towerShape: ShapeResource; private set
    lateinit var cupShape: ShapeResource; private set
    lateinit var trayShape: ShapeResource; private set
    /** Gaze-target markers (template GazeTargets): the visible frame on every tile the system hover is drawn on. */
    lateinit var markerParts: List<GazeTargets.Part>; private set
    lateinit var marker: Material; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        const val TOWER_COLLIDER_H = Layout.TOWER_H + 7 * Layout.PIECE_H

        /**
         * v0.1.4 gaze markers: the README default is a 70 % plate, but Ur's tiles carry the rosettes / eyes / dots the rules
         * and the look depend on, and a plate would hide them. So the marker is a rounded frame on the tile's rim: 96 % of
         * the tile outside, a 3.5 mm band (inside edge at 84 %, clear of every pattern), 1.5 mm proud.
         */
        val MARKER_STYLE = GazeTargets.Style(fraction = 0.96f, thick = 0.0015f, corner = 0.0015f, frameBand = 0.0035f)

        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                // q1-2 (design theme.ringLayering, piece R = 22.5 mm): lit R+3..R+8, selected R+2..R+11, target 22..28 mm;
                // the dark under-ring is 1 mm wider on each side only
                async(io) { r.ringPiece = MeshResource.createTorus(outerRingRadius = 0.0305f, innerRingRadius = 0.0255f).keep() },
                async(io) { r.ringSelect = MeshResource.createTorus(outerRingRadius = 0.0335f, innerRingRadius = 0.0245f).keep() },
                async(io) { r.ringSquare = MeshResource.createTorus(outerRingRadius = 0.0280f, innerRingRadius = 0.0220f).keep() },
                async(io) { r.ringCup = MeshResource.createTorus(outerRingRadius = 0.0460f, innerRingRadius = 0.0415f).keep() },
                async(io) { r.ringTower = MeshResource.createTorus(outerRingRadius = 0.0340f, innerRingRadius = 0.0290f).keep() },
                async(io) { r.glowPlane = MeshResource.createPlane(0.054f, 0.054f, 0.004f).keep() },
                async(io) { r.haloPiece = MeshResource.createTorus(outerRingRadius = 0.0315f, innerRingRadius = 0.0245f).keep() },
                async(io) { r.haloSelect = MeshResource.createTorus(outerRingRadius = 0.0345f, innerRingRadius = 0.0235f).keep() },
                async(io) { r.haloSquare = MeshResource.createTorus(outerRingRadius = 0.0290f, innerRingRadius = 0.0210f).keep() },
                async(io) { r.haloTower = MeshResource.createTorus(outerRingRadius = 0.0350f, innerRingRadius = 0.0280f).keep() },
                async(io) { r.dark = unlit(Color(0xFF1C1A17), 0.5f).keep() },
                async(io) { r.creamFaint = unlit(Color(0xFFF5F0E6), 0.5f).keep() },
                async(io) { r.cream = unlit(Color(0xFFF5F0E6), 0.95f).keep() },
                async(io) { r.blue = unlit(Color(0xFF3F7FD9), 0.95f).keep() },
                async(io) { r.orange = unlit(Color(0xFFD8842A), 0.95f).keep() },
                async(io) { r.gold = unlit(Color(0xFFE0B040), 0.95f).keep() },
                async(io) { r.amber = unlit(Color(0xFFE0A040), 0.95f).keep() },
                async(io) { r.red = unlit(Color(0xFFC8423F), 0.95f).keep() },
                async(io) { r.faint = unlit(Color(0xFF7FA8E8), 0.55f).keep() },
                async(io) { r.glow = unlit(Color(0xFFF2C94C), 0.6f).keep() },
                async(io) { r.squareShape = ShapeResource.createBox(Vector3(Layout.PITCH, 0.004f, Layout.PITCH)).keep() },
                async(io) { r.pieceShape = ShapeResource.createBox(Vector3(0.048f, 0.014f, 0.048f)).keep() },
                async(io) { r.towerShape = ShapeResource.createBox(Vector3(0.058f, TOWER_COLLIDER_H, 0.058f)).keep() },
                async(io) { r.cupShape = ShapeResource.createBox(Vector3(Layout.CUP_BOX, Layout.CUP_H + 0.01f, Layout.CUP_BOX)).keep() },
                async(io) { r.trayShape = ShapeResource.createBox(Vector3(Layout.TRAY_W, Layout.TRAY_H, Layout.TRAY_D)).keep() },
                async(io) { r.markerParts = GazeTargets.markerMeshes(Layout.TILE, MARKER_STYLE).also { ps -> ps.map { it.mesh }.distinct().forEach { it.keep() } } },
                async(io) {
                    // one shade darker than the shell tiles, opaque PBR (the system hover effect needs a visible opaque mesh)
                    r.marker = PhysicallyBasedMaterial.create().apply {
                        setBaseColor(Color(0xFFC9B48C).c4(1f)); setRoughness(0.7f); setMetallic(0f)
                        setEmissiveColor(Color4(0.05f, 0.045f, 0.035f, 1f))
                    }.keep()
                },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
