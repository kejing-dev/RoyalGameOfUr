package io.embodit.royalur.domain

/**
 * Finkel rules of the Royal Game of Ur (design-package gameSpec). Pure Kotlin, unit tested.
 *
 * Every piece is described by its progress along its own 14-square route: 0 = still in the start tower,
 * 1..14 = on the board, 15 = borne off (in the finish tower). Route of a side (design gameSpec.route):
 *   1..4  its own row, columns 4, 3, 2, 1   (4 = rosette in column 1, private)
 *   5..12 the middle row B, columns 1..8    (shared; 8 = centre rosette B4, safe)
 *   13    its own row, column 8
 *   14    its own row, column 7             (rosette, private)
 * Both sides map progress 5..12 to the same B column (p - 4), so "same progress in the shared lane" = same square.
 */
object Ur {
    const val NEAR = 0 // the user / near row C, obsidian pieces
    const val FAR = 1  // the opponent / far row A, red jasper pieces
    const val PIECES = 7
    const val START = 0
    const val OFF = 15
    const val CENTRE_ROSETTE = 8
    val ROSETTES = setOf(4, 8, 14)
    val SHARED = 5..12

    const val ROW_A = 0
    const val ROW_B = 1
    const val ROW_C = 2

    fun other(side: Int) = 1 - side
    fun ownRow(side: Int) = if (side == NEAR) ROW_C else ROW_A

    /** Board square (row 0..2 = A..C, column 1..8) of progress 1..14 for [side]. */
    fun square(side: Int, p: Int): Sq {
        require(p in 1..14) { "progress $p is not on the board" }
        return when (p) {
            in 1..4 -> Sq(ownRow(side), 5 - p)
            in 5..12 -> Sq(ROW_B, p - 4)
            13 -> Sq(ownRow(side), 8)
            else -> Sq(ownRow(side), 7)
        }
    }

    /** The progress of [side] that lands on [sq], or null when that square is not on [side]'s route. */
    fun progressOf(side: Int, sq: Sq): Int? = (1..14).firstOrNull { square(side, it) == sq }

    fun isRosette(sq: Sq): Boolean = (sq.row != ROW_B && (sq.col == 1 || sq.col == 7)) || (sq.row == ROW_B && sq.col == 4)

    /** The 20 playing squares (A1-A4, A7-A8, B1-B8, C1-C4, C7-C8). */
    val SQUARES: List<Sq> = buildList {
        for (r in 0..2) for (c in 1..8) if (r == ROW_B || c !in 5..6) add(Sq(r, c))
    }

    /** All legal moves of [side] for [roll] (0..4). Pieces in the start tower count as one choice (the top one). */
    fun legalMoves(pos: Pos, side: Int, roll: Int): List<Move> {
        if (roll <= 0) return emptyList()
        val mine = pos.of(side)
        val theirs = pos.of(other(side))
        val out = ArrayList<Move>()
        val seen = HashSet<Int>()
        for (i in mine.indices) {
            val from = mine[i]
            if (from == OFF || !seen.add(from)) continue
            val piece = if (from == START) mine.indices.filter { mine[it] == START }.max() else i
            val to = from + roll
            if (to > OFF) continue
            if (to < OFF && mine.any { it == to }) continue
            var captured = -1
            if (to in SHARED) {
                val j = theirs.indexOfFirst { it == to }
                if (j >= 0) {
                    if (to == CENTRE_ROSETTE) continue
                    captured = j
                }
            }
            out += Move(side, piece, from, to, captured, to in ROSETTES, to == OFF)
        }
        return out.sortedBy { it.from }
    }

    fun apply(pos: Pos, m: Move): Pos {
        val mine = pos.of(m.side).toMutableList()
        mine[m.piece] = m.to
        val theirs = pos.of(other(m.side)).toMutableList()
        if (m.captured >= 0) theirs[m.captured] = START
        return if (m.side == NEAR) Pos(mine, theirs) else Pos(theirs, mine)
    }

    fun winner(pos: Pos): Int? = when {
        pos.near.all { it == OFF } -> NEAR
        pos.far.all { it == OFF } -> FAR
        else -> null
    }

    /** Number of white tips up for four dice. */
    fun rollValue(dice: List<Boolean>): Int = dice.count { it }
}

/** A board square: row 0..2 (A far, B middle, C near), column 1..8 left to right as the user sees it. */
data class Sq(val row: Int, val col: Int) {
    val name: String get() = "${"ABC"[row]}$col"

    companion object {
        fun parse(s: String): Sq? {
            if (s.length < 2) return null
            val r = "ABC".indexOf(s[0].uppercaseChar())
            val c = s.substring(1).toIntOrNull() ?: return null
            if (r < 0 || c !in 1..8) return null
            return Sq(r, c).takeIf { it in Ur.SQUARES }
        }
    }
}

/** Both sides' pieces as progress values (index = piece id 0..6). */
data class Pos(val near: List<Int>, val far: List<Int>) {
    fun of(side: Int): List<Int> = if (side == Ur.NEAR) near else far
    fun inStart(side: Int): Int = of(side).count { it == Ur.START }
    fun off(side: Int): Int = of(side).count { it == Ur.OFF }
    fun onBoard(side: Int): Int = of(side).count { it in 1..14 }

    /** The piece of either side on [sq], as (side, piece index), or null. */
    fun at(sq: Sq): Pair<Int, Int>? {
        for (side in 0..1) {
            val p = Ur.progressOf(side, sq) ?: continue
            val i = of(side).indexOfFirst { it == p }
            if (i >= 0) return side to i
        }
        return null
    }

    companion object {
        val START = Pos(List(Ur.PIECES) { Ur.START }, List(Ur.PIECES) { Ur.START })
    }
}

/** One move: [piece] of [side] from progress [from] to [to]; [captured] = the opponent's piece sent home or -1. */
data class Move(
    val side: Int,
    val piece: Int,
    val from: Int,
    val to: Int,
    val captured: Int = -1,
    val rosette: Boolean = false,
    val off: Boolean = false,
) {
    val captures: Boolean get() = captured >= 0
}
