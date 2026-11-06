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

/**
 * Where everything sits in the volume (design-package spatialLayout). Scene space: origin on the volume floor,
 * centred in x and z, +Z toward the user, +Y up. The design's board coordinates (origin at the board centre) are
 * shifted by [BOARD_Z] so board + tray + tags fit the 0.42 m depth (design: content spans z -0.12 .. +0.30).
 *
 *   sceneRoot (volume floor)
 *   ├ tiltRoot  at the board's near-bottom edge, tipped [tiltDeg] about X (far edge up)
 *   │ └ boardRoot  board top centre; y = 0 is the box top, squares stand 2 mm proud
 *   ├ tray, cup, dice (never tilt), seat tag and dice tag (upright, in front of the board, left of the tray)
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
        const val BOARD_Z = -0.09f
        const val TRAY_W = 0.20f
        const val TRAY_D = 0.12f
        const val TRAY_H = 0.015f
        const val TRAY_FLOOR = 0.005f
        const val TRAY_Z = 0.125f
        const val CUP_X = 0.06f
        const val CUP_R = 0.04f
        const val CUP_H = 0.07f
        const val STEP_ARC = 0.03f
        const val CAPTURE_ARC = 0.12f
        /** Measured on the emulator (royal-ur v0.1.0, tag width against the 20 cm tray at the same depth): ~0.00069 m per dp. */
        const val DP_M = 0.00069f
        const val SEAT_W_DP = 136
        const val SEAT_H_DP = 52
        const val DICE_W_DP = 64
        const val DICE_H_DP = 48
        val SEAT_TAG = V3(-0.235f, 0f, TRAY_Z)
        val DICE_TAG = V3(-0.14f, 0f, TRAY_Z)
        val VOLUME = V3(0.60f, 0.30f, 0.42f)
        val LANES: List<V3> = listOf(V3(-0.055f, 0f, TRAY_Z - 0.025f), V3(-0.005f, 0f, TRAY_Z + 0.025f), V3(-0.005f, 0f, TRAY_Z - 0.025f), V3(-0.055f, 0f, TRAY_Z + 0.025f))
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

    /** Board-local centre of a square at its top surface. */
    fun square(sq: Sq): V3 = V3((sq.col - 4.5f) * PITCH, TILE_H + if (Ur.isRosette(sq)) ROSETTE_H else 0f, (sq.row - 1) * PITCH)

    /** Board-local centre of a piece standing on progress [p] of [side] (1..14). */
    fun pieceOn(side: Int, p: Int, lift: Float = 0f): V3 = square(Ur.square(side, p)) + V3(0f, PIECE_H / 2 + lift, 0f)

    /** Board-local base centre of a tower: [finish] = column 6 slot, otherwise the start tower in column 5. */
    fun tower(side: Int, finish: Boolean): V3 = V3(((if (finish) 6 else 5) - 4.5f) * PITCH, 0f, (Ur.ownRow(side) - 1) * PITCH)

    /** Board-local centre of stack slot [i] (0 = bottom) on a tower. */
    fun slot(side: Int, finish: Boolean, i: Int): V3 = tower(side, finish) + V3(0f, TOWER_H + i * PIECE_H + PIECE_H / 2, 0f)

    /** Board-local centre of a piece at progress [p] where [stackIndex] is its place in a tower (0 / 15). */
    fun pieceAt(side: Int, p: Int, stackIndex: Int, lift: Float = 0f): V3 = when (p) {
        Ur.START -> slot(side, false, stackIndex) + V3(0f, lift, 0f)
        Ur.OFF -> slot(side, true, stackIndex) + V3(0f, lift, 0f)
        else -> pieceOn(side, p, lift)
    }

    /** A flight between two board-local points at time fraction [t]: a parabola peaking [arc] above the chord. */
    fun arc(a: V3, b: V3, t: Float, arc: Float): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * arc * k * (1f - k), 0f)
    }

    fun trayCentre(): V3 = V3(0f, 0f, TRAY_Z)
    fun cupBase(): V3 = V3(CUP_X, TRAY_FLOOR, TRAY_Z)

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

    /** Scene-space boxes of our own models (D35): the board box (tilted, bounded), the four towers with full stacks, tray, cup. */
    fun modelBoxes(): Map<String, Box3> {
        val hx = BOX_W / 2; val hz = BOX_D / 2
        val box = listOf(-hx, hx).flatMap { x -> listOf(-hz, hz).flatMap { z -> listOf(-BOX_T, TILE_H + ROSETTE_H).map { y -> toScene(V3(x, y, z)) } } }
        val out = LinkedHashMap<String, Box3>()
        out["board"] = Box3.of(box)
        for (side in 0..1) for (finish in listOf(false, true)) {
            val b = tower(side, finish)
            val pts = listOf(-1f, 1f).flatMap { sx -> listOf(-1f, 1f).flatMap { sz -> listOf(0f, TOWER_H + 7 * PIECE_H).map { y -> toScene(b + V3(sx * TOWER_W / 2, y, sz * TOWER_W / 2)) } } }
            out["tower:$side:${if (finish) "finish" else "start"}"] = Box3.of(pts)
        }
        out["tray"] = Box3.around(trayCentre() + V3(0f, TRAY_H / 2, 0f), V3(TRAY_W, TRAY_H, TRAY_D))
        out["cup"] = Box3.around(cupBase() + V3(0f, CUP_H / 2, 0f), V3(CUP_R * 2, CUP_H, CUP_R * 2))
        return out
    }

    /** Volume bounds in scene space (floor at y = 0). */
    fun volume(): Box3 = Box3(V3(-VOLUME.x / 2, 0f, -VOLUME.z / 2), V3(VOLUME.x / 2, VOLUME.y, VOLUME.z / 2))

    /** Design eye positions (spatialLayout.panelGeometryCheck.eyePositions), in scene space. */
    fun eyes(): Map<String, V3> {
        val c = toScene(V3(0f, 0f, 0f))
        val sit = V3(0f, 0.45f, 0.55f); val stand = V3(0f, 0.75f, 0.55f)
        fun yaw(v: V3, deg: Float): V3 { val a = Math.toRadians(deg.toDouble()); return V3((v.z * kotlin.math.sin(a)).toFloat(), v.y, (v.z * kotlin.math.cos(a)).toFloat()) }
        return linkedMapOf(
            "sit" to c + sit, "stand" to c + stand, "sitLeft30" to c + yaw(sit, -30f), "sitRight30" to c + yaw(sit, 30f),
            "standLeft30" to c + yaw(stand, -30f), "standRight30" to c + yaw(stand, 30f),
        )
    }
}
