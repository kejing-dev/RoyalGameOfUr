package io.embodit.royalur.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Pose of a die: position (metres, relative to its lane centre on the tray floor, +Y up) and quaternion (x, y, z, w). */
class DicePose(val position: DoubleArray, val quaternion: DoubleArray)

/** A finished throw of one tetrahedral die: poses from release to rest at [dt] steps; [white] = a white tip ends up. */
class DiceThrow(val white: Boolean, val frames: List<DicePose>, val dt: Double) {
    val duration: Double get() = (frames.size - 1) * dt

    fun sample(time: Double): DicePose {
        val t = time.coerceIn(0.0, duration)
        val index = (t / dt).toInt()
        if (index >= frames.size - 1) return frames.last()
        val a = frames[index]
        val b = frames[index + 1]
        val u = (t - index * dt) / dt
        val p = DoubleArray(3) { a.position[it] + (b.position[it] - a.position[it]) * u }
        return DicePose(p, Tetra.slerp(a.quaternion, b.quaternion, u))
    }

    val rest: DicePose get() = frames.last()
}

/**
 * Deterministic rigid-tetrahedron throws: Cloud Air's DiceSolver (cube, 240 Hz, vertex contacts, Coulomb friction,
 * sleeping) rewritten for the four-sided pyramid dice of Ur (design N1).
 *
 * The game commits each die's result first. A result-independent throw is simulated; then every frame's orientation
 * is right-multiplied by one constant symmetry of the tetrahedron (a half turn about Z, which swaps the white tips
 * v0/v1 with the black tips v3/v2) when the tip that naturally ends up on top is of the wrong colour. The motion is
 * never steered and the last frame never flips.
 *
 * Model frame (= die.glb): regular tetrahedron, centroid at the origin, vertices s·(1,1,1) v0 white, s·(1,-1,-1) v1
 * white, s·(-1,1,-1) v2 black, s·(-1,-1,1) v3 black. Each die falls in its own 5 x 5 cm lane of the tray.
 */
object Tetra {
    /** Edge in metres (design gameSpec.dice: 2.4 cm). */
    const val EDGE_M = 0.024
    /** The solver runs at 25x scale (edge 0.6 units, Cloud Air's proven contact constants); output is metres. */
    private const val SCALE = 25.0
    private const val EDGE = EDGE_M * SCALE
    private val S = EDGE / (2 * sqrt(2.0))
    const val DT = 1.0 / 240
    private val INV_I = 20.0 / (EDGE * EDGE)
    /** Half the lane (metres): four 5 x 5 cm lanes in the tray's dice area. */
    const val LANE_HALF_M = 0.025
    private const val LANE_HALF = LANE_HALF_M * SCALE

    /** Unit vertex directions; v0, v1 carry the white tips. */
    val VERTICES: List<DoubleArray> = listOf(
        doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, -1.0, -1.0), doubleArrayOf(-1.0, 1.0, -1.0), doubleArrayOf(-1.0, -1.0, 1.0),
    )
    fun isWhite(vertex: Int) = vertex <= 1

    /** Height of the centroid above the floor when the die rests on a face (metres). */
    val REST_HEIGHT_M: Double = EDGE_M / (2 * sqrt(6.0))

    private val SWAP = doubleArrayOf(0.0, 0.0, 1.0, 0.0) // half turn about Z: v0<->v3, v1<->v2

    // ---- vector helpers ----
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun length(a: DoubleArray): Double { var s = 0.0; for (v in a) s += v * v; return sqrt(s) }
    private fun normalize(a: DoubleArray): DoubleArray { val l = length(a); return DoubleArray(a.size) { a[it] / l } }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    fun rotateVector(q: DoubleArray, v: DoubleArray): DoubleArray {
        val qv = doubleArrayOf(q[0], q[1], q[2])
        val t = cross(qv, v).also { for (i in 0..2) it[i] *= 2 }
        val c = cross(qv, t)
        return DoubleArray(3) { v[it] + q[3] * t[it] + c[it] }
    }

    /** The vertex pointing most upward and how vertical it is (1 = the die rests flat on the opposite face). */
    fun topVertex(q: DoubleArray): Pair<Int, Double> {
        var best = -2.0
        var index = 0
        for ((i, v) in VERTICES.withIndex()) {
            val up = rotateVector(q, v)[1] / sqrt(3.0)
            if (up > best) { best = up; index = i }
        }
        return index to best
    }

    fun slerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        var cosine = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        var end = b
        if (cosine < 0) { cosine = -cosine; end = DoubleArray(4) { -b[it] } }
        if (cosine > 0.9995) return normalize(DoubleArray(4) { a[it] + (end[it] - a[it]) * t })
        val angle = acos(min(1.0, cosine))
        val s = sin(angle)
        return DoubleArray(4) { (a[it] * sin((1 - t) * angle) + end[it] * sin(t * angle)) / s }
    }

    private class Plane(val n: DoubleArray, val d: Double, val t1: DoubleArray, val t2: DoubleArray, val floor: Boolean)

    private val planes = listOf(
        Plane(doubleArrayOf(0.0, 1.0, 0.0), 0.0, doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), true),
        Plane(doubleArrayOf(1.0, 0.0, 0.0), -LANE_HALF, doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), false),
        Plane(doubleArrayOf(-1.0, 0.0, 0.0), -LANE_HALF, doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), false),
        Plane(doubleArrayOf(0.0, 0.0, 1.0), -LANE_HALF, doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), false),
        Plane(doubleArrayOf(0.0, 0.0, -1.0), -LANE_HALF, doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), false),
    )

    private class Body(val p: DoubleArray, val v: DoubleArray, val w: DoubleArray, var q: DoubleArray)
    private class Contact(val plane: Plane, val r: DoubleArray, val target: Double, val mn: Double, val mt1: Double, val mt2: Double) {
        var normal = 0.0; var friction1 = 0.0; var friction2 = 0.0
    }
    private class Simulation(val frames: List<DicePose>, val slept: Boolean, val maxPenetration: Double)

    private fun velocityAt(b: Body, r: DoubleArray): DoubleArray { val wr = cross(b.w, r); return DoubleArray(3) { b.v[it] + wr[it] } }
    private fun effectiveMass(r: DoubleArray, n: DoubleArray): Double { val rn = cross(r, n); return 1 / (1 + INV_I * dot(rn, rn)) }
    private fun impulse(b: Body, r: DoubleArray, n: DoubleArray, amount: Double) {
        val torque = cross(r, n)
        for (i in 0..2) { b.v[i] += n[i] * amount; b.w[i] += torque[i] * amount * INV_I }
    }

    private fun pose(b: Body) = DicePose(DoubleArray(3) { b.p[it] / SCALE }, b.q.copyOf())

    private fun isSettled(b: Body): Boolean = length(b.v) < 0.05 && length(b.w) < 0.07 && topVertex(b.q).second > 0.9995

    private fun simulate(seed: Int): Simulation {
        val rng = Random(seed)
        fun r() = rng.nextDouble()
        val axis = normalize(doubleArrayOf(r() - 0.5, r() - 0.5, r() - 0.5))
        val angle = r() * Math.PI * 2
        // Dropped from about 3-4 cm, sliding out of the cup leftwards (-X), spinning.
        val b = Body(
            p = doubleArrayOf((r() - 0.3) * 0.3, 0.85 + r() * 0.2, (r() - 0.5) * 0.3),
            v = doubleArrayOf(-0.4 - r() * 0.5, 0.2 + r() * 0.3, (r() - 0.5) * 0.6),
            w = doubleArrayOf((r() - 0.5) * 18, (r() - 0.5) * 12, (r() - 0.5) * 18),
            q = doubleArrayOf(axis[0] * sin(angle / 2), axis[1] * sin(angle / 2), axis[2] * sin(angle / 2), cos(angle / 2)),
        )
        val corners = VERTICES.map { v -> DoubleArray(3) { v[it] * S } }
        val frames = ArrayList<DicePose>(700)
        frames += pose(b)
        var quiet = 0
        var maxPenetration = 0.0
        for (step in 1..900) {
            b.v[1] -= 9.81 * DT
            for (i in 0..2) { b.p[i] += b.v[i] * DT; b.w[i] *= exp(-0.025 * DT) }
            val dq = multiply(doubleArrayOf(b.w[0], b.w[1], b.w[2], 0.0), b.q)
            b.q = normalize(DoubleArray(4) { b.q[it] + 0.5 * DT * dq[it] })
            val contacts = ArrayList<Contact>()
            for (local in corners) {
                val rr = rotateVector(b.q, local)
                val world = DoubleArray(3) { rr[it] + b.p[it] }
                for (plane in planes) {
                    val distance = dot(world, plane.n) - plane.d
                    if (distance > 0.0015) continue
                    val vn = dot(velocityAt(b, rr), plane.n)
                    maxPenetration = max(maxPenetration, -distance)
                    val bounce = if (vn < -0.7) -vn * (if (plane.floor) 0.3 else 0.25) else 0.0
                    val bias = min(1.2, 0.18 * max(0.0, -distance - 0.00035) / DT)
                    contacts += Contact(plane, rr, max(bounce, bias), effectiveMass(rr, plane.n), effectiveMass(rr, plane.t1), effectiveMass(rr, plane.t2))
                }
            }
            repeat(14) {
                for (c in contacts) {
                    val next = max(0.0, c.normal + (c.target - dot(velocityAt(b, c.r), c.plane.n)) * c.mn)
                    impulse(b, c.r, c.plane.n, next - c.normal); c.normal = next
                    val v = velocityAt(b, c.r)
                    var f1 = c.friction1 - dot(v, c.plane.t1) * c.mt1
                    var f2 = c.friction2 - dot(v, c.plane.t2) * c.mt2
                    val magnitude = sqrt(f1 * f1 + f2 * f2)
                    val limit = 0.6 * c.normal
                    if (magnitude > limit && magnitude > 0) { f1 *= limit / magnitude; f2 *= limit / magnitude }
                    impulse(b, c.r, c.plane.t1, f1 - c.friction1); impulse(b, c.r, c.plane.t2, f2 - c.friction2)
                    c.friction1 = f1; c.friction2 = f2
                }
            }
            val onFloor = contacts.any { it.plane.floor }
            if (onFloor) for (i in 0..2) { b.w[i] *= exp(-1.5 * DT); b.v[i] *= exp(-0.6 * DT) }
            quiet = if (onFloor && isSettled(b)) quiet + 1 else 0
            if (quiet >= 24) {
                b.v.fill(0.0); b.w.fill(0.0)
                frames += pose(b)
                return Simulation(frames, true, maxPenetration)
            }
            frames += pose(b)
        }
        return Simulation(frames, false, maxPenetration)
    }

    private fun naturalThrow(seed: Int): Simulation {
        var fallback: Simulation? = null
        for (attempt in 0 until 12) {
            val sim = simulate(seed + attempt * 0x9e3779b9.toInt())
            if (sim.slept && sim.maxPenetration < 0.04) return sim
            if (sim.slept && fallback == null) fallback = sim
        }
        return fallback ?: restSimulation()
    }

    /** Last resort (never seen in tests): lying still on a face. */
    private fun restSimulation(): Simulation {
        val q = restQuaternion(0)
        val p = doubleArrayOf(0.0, REST_HEIGHT_M, 0.0)
        return Simulation(listOf(DicePose(p, q), DicePose(p, q)), true, 0.0)
    }

    /** Orientation that puts [vertex] straight up (the die on the opposite face). */
    fun restQuaternion(vertex: Int): DoubleArray {
        val from = normalize(VERTICES[vertex])
        val to = doubleArrayOf(0.0, 1.0, 0.0)
        val c = cross(from, to)
        return normalize(doubleArrayOf(c[0], c[1], c[2], 1 + dot(from, to)))
    }

    /** A throw ending with a white tip up when [white]. The same [seed] always gives the same motion. */
    fun createThrow(white: Boolean, seed: Int): DiceThrow {
        val natural = naturalThrow(seed)
        val landed = topVertex(natural.frames.last().quaternion).first
        val frames = if (isWhite(landed) == white) natural.frames
        else natural.frames.map { DicePose(it.position, normalize(multiply(it.quaternion, SWAP))) }
        return DiceThrow(white, frames, DT)
    }
}

/**
 * One roll of the four dice: each die's result is decided first (a fair coin each, so 0..4 follows
 * 1/16 · 4/16 · 6/16 · 4/16 · 1/16), plus a motion seed per die for [Tetra.createThrow].
 */
data class Roll(val dice: List<Boolean>, val seeds: List<Int>) {
    val value: Int get() = dice.count { it }

    companion object {
        fun random(rng: Random): Roll = Roll(List(4) { rng.nextBoolean() }, List(4) { rng.nextInt() })

        /** A roll with exactly [n] white tips (debug `roll N`), the dice that show white chosen at random. */
        fun forced(n: Int, rng: Random): Roll {
            val k = n.coerceIn(0, 4)
            val idx = (0..3).shuffled(rng).take(k).toSet()
            return Roll(List(4) { it in idx }, List(4) { rng.nextInt() })
        }
    }
}
