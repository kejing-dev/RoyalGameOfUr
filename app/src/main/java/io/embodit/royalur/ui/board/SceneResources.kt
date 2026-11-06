package io.embodit.royalur.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.royalur.domain.Layout
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
    lateinit var dark: Material; private set

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
    lateinit var boardShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        const val TOWER_COLLIDER_H = Layout.TOWER_H + 7 * Layout.PIECE_H

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
                async(io) { r.ringPiece = MeshResource.createTorus(outerRingRadius = 0.0272f, innerRingRadius = 0.0248f).keep() },
                async(io) { r.ringSelect = MeshResource.createTorus(outerRingRadius = 0.0300f, innerRingRadius = 0.0250f).keep() },
                async(io) { r.ringSquare = MeshResource.createTorus(outerRingRadius = 0.0262f, innerRingRadius = 0.0226f).keep() },
                async(io) { r.ringCup = MeshResource.createTorus(outerRingRadius = 0.0500f, innerRingRadius = 0.0455f).keep() },
                async(io) { r.ringTower = MeshResource.createTorus(outerRingRadius = 0.0330f, innerRingRadius = 0.0290f).keep() },
                async(io) { r.glowPlane = MeshResource.createPlane(0.054f, 0.054f, 0.004f).keep() },
                // dark halos under the light rings: a cream ring on a cream tile is only 1.1:1 (D13 graphical >= 3:1)
                async(io) { r.haloPiece = MeshResource.createTorus(outerRingRadius = 0.0300f, innerRingRadius = 0.0234f).keep() },
                async(io) { r.haloSelect = MeshResource.createTorus(outerRingRadius = 0.0326f, innerRingRadius = 0.0240f).keep() },
                async(io) { r.haloSquare = MeshResource.createTorus(outerRingRadius = 0.0290f, innerRingRadius = 0.0212f).keep() },
                async(io) { r.dark = unlit(Color(0xFF1C1A17)).keep() },
                async(io) { r.cream = unlit(Color(0xFFF5F0E6)).keep() },
                async(io) { r.blue = unlit(Color(0xFF3F7FD9)).keep() },
                async(io) { r.orange = unlit(Color(0xFFD8842A)).keep() },
                async(io) { r.gold = unlit(Color(0xFFE0B040)).keep() },
                async(io) { r.amber = unlit(Color(0xFFE0A040)).keep() },
                async(io) { r.red = unlit(Color(0xFFC8423F)).keep() },
                async(io) { r.faint = unlit(Color(0xFF7FA8E8), 0.55f).keep() },
                async(io) { r.glow = unlit(Color(0xFFF2C94C), 0.6f).keep() },
                async(io) { r.squareShape = ShapeResource.createBox(Vector3(0.060f, 0.003f, 0.060f)).keep() },
                async(io) { r.pieceShape = ShapeResource.createBox(Vector3(0.048f, 0.014f, 0.048f)).keep() },
                async(io) { r.towerShape = ShapeResource.createBox(Vector3(0.058f, TOWER_COLLIDER_H, 0.058f)).keep() },
                async(io) { r.cupShape = ShapeResource.createBox(Vector3(0.09f, 0.08f, 0.09f)).keep() },
                async(io) { r.trayShape = ShapeResource.createBox(Vector3(Layout.TRAY_W, Layout.TRAY_H, Layout.TRAY_D)).keep() },
                async(io) { r.boardShape = ShapeResource.createBox(Vector3(Layout.BOX_W, Layout.BOX_T, Layout.BOX_D)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
