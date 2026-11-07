package io.embodit.royalur.domain

import kotlin.math.cos
import kotlin.math.sin

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/** An axis-aligned box in scene space. */
data class Box3(val min: V3, val max: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > min.x - eps && p.x < max.x + eps && p.y > min.y - eps && p.y < max.y + eps && p.z > min.z - eps && p.z < max.z + eps

    /** Does the segment a-b pass through the box (slab test)? */
    fun hitBySegment(a: V3, b: V3): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ad = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val lo = floatArrayOf(min.x, min.y, min.z); val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (kotlin.math.abs(dd[i]) < 1e-9f) { if (ad[i] < lo[i] || ad[i] > hi[i]) return false; continue }
            var ta = (lo[i] - ad[i]) / dd[i]; var tb = (hi[i] - ad[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    companion object {
        fun around(c: V3, size: V3) = Box3(c - size * 0.5f, c + size * 0.5f)
        fun of(points: List<V3>) = Box3(V3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }), V3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
    }
}

/** A point in scene space plus whether it rides on the tilted board (pieces and rings there tip with it). */
data class Spot(val at: V3, val tilted: Boolean)

/**
 * Where everything sits in the volume (design-package v1.2 spatialLayout). Scene space: origin on the volume floor,
 * centred in x and z, +Z toward the user, +Y up. The design's sceneRoot (origin = board centre projected on the floor)
 * is this scene shifted by [D_Z]: design (x, y, z) = scene (x, y, z + 0.09).
 *
 *   sceneRoot (volume floor)
 *   ├ tiltRoot  at the board's near-bottom edge, tipped [tiltDeg] about X (far edge up)
 *   │ └ boardRoot  board top centre; the far side's two towers stay in the A-row gaps (they tip with the board)
 *   ├ your start tower (left of the board), your finish tower and the cup (right of the board): never tilt (q1-1)
 *   └ tray in front on the right, seat tag + dice tag in front (the low zone: nothing taller than 0.028 m)
 */
class Layout(val tiltDeg: Int = 15) {
    companion object {
        const val PITCH = 0.06f
        const val BOX_W = 0.53f
        const val BOX_D = 0.23f
        const val BOX_T = 0.03f
        const val TILE = 0.056f
        const val TILE_H = 0.002f
        const val ROSETTE_H = 0.001f
        const val PIECE_D = 0.045f
        const val PIECE_H = 0.012f
        const val TOWER_W = 0.055f
        const val TOWER_H = 0.02f
        const val LIFT = 0.008f
        /** design z = scene z + 0.09 */
        const val D_Z = -0.09f
        const val BOARD_Z = D_Z
        const val TRAY_W = 0.20f
        const val TRAY_D = 0.12f
        const val TRAY_H = 0.015f
        const val TRAY_FLOOR = 0.005f
        /** v0.1.4 headset feedback: lower (7 -> 5 cm) and slimmer (base r 4 -> 3.6 cm) so rays to the finish tower clear it. */
        const val CUP_R = 0.036f
        const val CUP_H = 0.05f
        /** Cup tap collider (wider than the cup) and its obstacle box. */
        const val CUP_BOX = 0.082f
        const val STEP_ARC = 0.03f
        const val CAPTURE_ARC = 0.12f
        /** Low zone in front of the board (design occlusionRule rule 1). */
        const val LOW_ZONE = 0.028f
        /** Measured on the emulator (royal-ur v0.1.0, tag width against the 20 cm tray at the same depth): ~0.00069 m per dp. */
        const val DP_M = 0.00069f
        const val SEAT_W_DP = 136
        const val SEAT_H_DP = 38
        const val DICE_W_DP = 64
        const val DICE_H_DP = 38
        val TRAY = V3(0.16f, 0f, 0.195f + D_Z)
        /** v0.1.4: 2 cm nearer the board's right edge and 7 mm toward you (front edge at the rule-2 limit z +0.08). */
        val CUP = V3(0.315f, 0f, 0.039f + D_Z)
        /** Where the cup slides to pour (over the tray's right end). */
        val CUP_POUR = V3(0.30f, 0f, 0.17f + D_Z)
        val NEAR_START = V3(-0.32f, 0f, 0.03f + D_Z)
        val NEAR_FINISH = V3(0.32f, 0f, -0.06f + D_Z)
        val SEAT_TAG = V3(-0.17f, 0f, 0.195f + D_Z)
        val DICE_TAG = V3(0.015f, 0f, 0.195f + D_Z)
        val VOLUME = V3(0.80f, 0.30f, 0.42f)
        val LANES: List<V3> = listOf(V3(0.135f, 0f, TRAY.z - 0.025f), V3(0.185f, 0f, TRAY.z + 0.025f), V3(0.185f, 0f, TRAY.z - 0.025f), V3(0.135f, 0f, TRAY.z + 0.025f))
    }

    val theta: Float = Math.toRadians(tiltDeg.toDouble()).toFloat()
    /** tiltRoot in scene space: the near-bottom edge of the box. */
    val tiltOrigin: V3 = V3(0f, 0f, BOARD_Z + BOX_D / 2)
    /** boardRoot relative to tiltRoot before the tilt: up the box thickness, back half the depth. */
    val boardOffset: V3 = V3(0f, BOX_T, -BOX_D / 2)

    private fun rotX(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    fun toScene(local: V3): V3 = tiltOrigin + rotX(boardOffset + local, theta)
    fun toLocal(scene: V3): V3 = rotX(scene - tiltOrigin, -theta) - boardOffset

    /** Board-local centre of a square at its top surface. */
    fun square(sq: Sq): V3 = V3((sq.col - 4.5f) * PITCH, TILE_H + if (Ur.isRosette(sq)) ROSETTE_H else 0f, (sq.row - 1) * PITCH)

    /** Board-local centre of a piece standing on progress [p] of [side] (1..14). */
    fun pieceOn(side: Int, p: Int, lift: Float = 0f): V3 = square(Ur.square(side, p)) + V3(0f, PIECE_H / 2 + lift, 0f)

    /** The far side's towers sit in the A-row gaps (board-local base centre): start column 5, finish column 6. */
    fun farTower(finish: Boolean): V3 = V3(((if (finish) 6 else 5) - 4.5f) * PITCH, 0f, -PITCH)

    /** Base centre of a tower as a scene spot: yours beside the board (flat), the far side's in the A-row gaps (tilted). */
    fun tower(side: Int, finish: Boolean): Spot =
        if (side == Ur.NEAR) Spot(if (finish) NEAR_FINISH else NEAR_START, false) else Spot(toScene(farTower(finish)), true)

    /** Centre of stack slot [i] (0 = bottom) on a tower, plus [lift] along the tower's up. */
    fun slot(side: Int, finish: Boolean, i: Int, lift: Float = 0f): Spot {
        val up = TOWER_H + i * PIECE_H + PIECE_H / 2 + lift
        return if (side == Ur.NEAR) Spot((if (finish) NEAR_FINISH else NEAR_START) + V3(0f, up, 0f), false)
        else Spot(toScene(farTower(finish) + V3(0f, up, 0f)), true)
    }

    /** A piece on the board at progress [p] of [side]. */
    fun onBoard(side: Int, p: Int, lift: Float = 0f): Spot = Spot(toScene(pieceOn(side, p, lift)), true)

    /** Where a piece at progress [p] stands; [stackIndex] is its place in a tower (0 / 15). */
    fun pieceAt(side: Int, p: Int, stackIndex: Int, lift: Float = 0f): Spot = when (p) {
        Ur.START -> slot(side, false, stackIndex, lift)
        Ur.OFF -> slot(side, true, stackIndex, lift)
        else -> onBoard(side, p, lift)
    }

    /** A square's top centre in scene space (+[up] along the board normal). */
    fun squareSpot(sq: Sq, up: Float = 0f): Spot = Spot(toScene(square(sq) + V3(0f, up, 0f)), true)

    /** A flight between two scene points at time fraction [t]: a parabola peaking [arc] above the chord. */
    fun arc(a: V3, b: V3, t: Float, arc: Float): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * arc * k * (1f - k), 0f)
    }

    fun trayCentre(): V3 = TRAY
    fun cupBase(): V3 = CUP

    fun seatSize(): V3 = V3(SEAT_W_DP * DP_M, SEAT_H_DP * DP_M, 0f)
    fun diceTagSize(): V3 = V3(DICE_W_DP * DP_M, DICE_H_DP * DP_M, 0f)
    /** Tag centres: standing on the volume floor, facing +Z. */
    fun seatCentre(): V3 = SEAT_TAG + V3(0f, seatSize().y / 2, 0f)
    fun diceTagCentre(): V3 = DICE_TAG + V3(0f, diceTagSize().y / 2, 0f)

    /** The four corners of a tag rectangle (facing +Z). */
    fun corners(centre: V3, size: V3): List<V3> = listOf(
        centre + V3(-size.x / 2, -size.y / 2, 0f), centre + V3(size.x / 2, -size.y / 2, 0f),
        centre + V3(size.x / 2, size.y / 2, 0f), centre + V3(-size.x / 2, size.y / 2, 0f),
    )

    /** Obstacles for the occlusion rule: flat (scene AABB) or tilted (AABB in board-local space). */
    class Obstacle(val name: String, val box: Box3, val local: Boolean)

    fun obstacles(): List<Obstacle> {
        val th = TOWER_H + 7 * PIECE_H
        val out = ArrayList<Obstacle>()
        for (finish in listOf(false, true)) {
            val b = farTower(finish)
            out += Obstacle("far tower ${if (finish) "finish" else "start"}", Box3(b + V3(-TOWER_W / 2, 0f, -TOWER_W / 2), b + V3(TOWER_W / 2, th, TOWER_W / 2)), true)
            val n = if (finish) NEAR_FINISH else NEAR_START
            out += Obstacle("near tower ${if (finish) "finish" else "start"}", Box3(n + V3(-TOWER_W / 2, 0f, -TOWER_W / 2), n + V3(TOWER_W / 2, th, TOWER_W / 2)), false)
        }
        out += Obstacle("cup", Box3.around(CUP + V3(0f, (CUP_H + 0.005f) / 2, 0f), V3(CUP_BOX, CUP_H + 0.005f, CUP_BOX)), false)
        out += Obstacle("tray+dice", Box3.around(TRAY + V3(0f, 0.012f, 0f), V3(TRAY_W, 0.024f, TRAY_D)), false)
        val s = seatSize(); val d = diceTagSize()
        out += Obstacle("seat tag", Box3.around(seatCentre(), V3(s.x, s.y, 0.004f)), false)
        out += Obstacle("dice tag", Box3.around(diceTagCentre(), V3(d.x, d.y, 0.004f)), false)
        return out
    }

    /** True when the segment eye -> target passes through any obstacle. */
    fun blockedBy(eye: V3, target: V3): String? = obstacles().firstOrNull { o ->
        if (o.local) o.box.hitBySegment(toLocal(eye), toLocal(target)) else o.box.hitBySegment(eye, target)
    }?.name

    /** Volume bounds in scene space (floor at y = 0). */
    fun volume(): Box3 = Box3(V3(-VOLUME.x / 2, 0f, -VOLUME.z / 2), V3(VOLUME.x / 2, VOLUME.y, VOLUME.z / 2))

    /**
     * Eyes (design occlusionRule): the measured emulator reset (eye level with the volume centre, 0.7 / 1.2 / 1.9 / 2.5 m
     * in front, azimuth 0 / ±30°; about 4° down to the board at 1.9 m) plus the design's sit / stand / sit ±30°.
     */
    fun eyes(): Map<String, V3> {
        val out = LinkedHashMap<String, V3>()
        for (d in listOf(0.7f, 1.2f, 1.9f, 2.5f)) for (az in listOf(0, -30, 30)) {
            val a = Math.toRadians(az.toDouble())
            out["reset d=$d az=$az"] = V3((d * kotlin.math.sin(a)).toFloat(), 0.15f, (d * kotlin.math.cos(a)).toFloat())
        }
        out["sit"] = V3(0f, 0.48f, 0.55f + D_Z); out["stand"] = V3(0f, 0.78f, 0.55f + D_Z)
        out["sitL"] = V3(-0.275f, 0.48f, 0.476f + D_Z); out["sitR"] = V3(0.275f, 0.48f, 0.476f + D_Z)
        return out
    }
}
