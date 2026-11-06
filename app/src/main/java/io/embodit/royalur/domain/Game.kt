package io.embodit.royalur.domain

enum class Mode { AI, TWO_PLAYER }

enum class FirstPick { ME, THEM, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** One game's setup with the first mover resolved. In AI mode the user is always the near side. */
data class GameConfig(
    val mode: Mode = Mode.AI,
    val aiLevel: Int = Ai.EASY,
    val firstSide: Int = Ur.NEAR,
) {
    fun isHuman(side: Int): Boolean = mode == Mode.TWO_PLAYER || side == Ur.NEAR
}

/** The new-game page's choices (remembered between games; a random first mover only goes into the game, D30). */
data class GameSetup(
    val mode: Mode = Mode.AI,
    val aiLevel: Int = Ai.EASY,
    val first: FirstPick = FirstPick.ME,
)

/** App settings (SharedPreferences). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val tilt: Int = 15,
    val firstRunDone: Boolean = false,
    val coachDone: Boolean = false,
    val coachRosette: Boolean = false,
    val coachCaptured: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
) {
    companion object {
        val TILTS = listOf(0, 10, 15)
    }
}

/**
 * Everything a turn boundary needs to be rebuilt: the position, who is to move, the committed roll if the dice
 * have been thrown (then the side is choosing a piece, S05), the turn number, stats and the last move.
 */
data class Snap(
    val pos: Pos,
    val side: Int,
    val roll: Roll?,
    val turnNo: Int,
    val captures: List<Int>,
    val rosettes: List<Int>,
    val last: Move?,
)

/** What a game looks like on disk (files/game.txt). */
data class SavedGame(
    val config: GameConfig,
    val now: Snap,
    val undo: List<Snap> = emptyList(),
    val elapsedMs: Long = 0,
    val hintOn: Boolean = false,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Line codec: `ur1|mode|level|first|<snap>|elapsed|hint|<snap>;<snap>...` where a snap is
 * `pos,side,roll,turn,cN,cF,rN,rF,last`: pos = 14 hex digits (near 7 then far 7), roll = `wwww:s1:s2:s3:s4` or `-`,
 * last = `side.piece.from.to.cap.ros.off` or `-`.
 */
object SaveCodec {
    private fun pos(p: Pos) = (p.near + p.far).joinToString("") { it.toString(16) }
    private fun parsePos(s: String): Pos {
        require(s.length == 14)
        val v = s.map { it.digitToInt(16) }
        require(v.all { it in 0..15 })
        return Pos(v.take(7), v.drop(7))
    }

    private fun roll(r: Roll?) = r?.let { it.dice.joinToString("") { d -> if (d) "1" else "0" } + ":" + it.seeds.joinToString(":") } ?: "-"
    private fun parseRoll(s: String): Roll? {
        if (s == "-") return null
        val f = s.split(":")
        require(f.size == 5 && f[0].length == 4)
        return Roll(f[0].map { it == '1' }, f.drop(1).map { it.toInt() })
    }

    private fun move(m: Move?) = m?.let { listOf(it.side, it.piece, it.from, it.to, it.captured, if (it.rosette) 1 else 0, if (it.off) 1 else 0).joinToString(".") } ?: "-"
    private fun parseMove(s: String): Move? {
        if (s == "-") return null
        val f = s.split(".").map { it.toInt() }
        return Move(f[0], f[1], f[2], f[3], f[4], f[5] == 1, f[6] == 1)
    }

    fun snap(s: Snap): String = listOf(
        pos(s.pos), s.side.toString(), roll(s.roll), s.turnNo.toString(),
        s.captures[0].toString(), s.captures[1].toString(), s.rosettes[0].toString(), s.rosettes[1].toString(), move(s.last),
    ).joinToString(",")

    fun parseSnap(t: String): Snap {
        val f = t.split(",")
        require(f.size == 9)
        return Snap(parsePos(f[0]), f[1].toInt().coerceIn(0, 1), parseRoll(f[2]), f[3].toInt(), listOf(f[4].toInt(), f[5].toInt()), listOf(f[6].toInt(), f[7].toInt()), parseMove(f[8]))
    }

    fun encode(g: SavedGame): String = listOf(
        "ur1", g.config.mode.name, g.config.aiLevel.toString(), g.config.firstSide.toString(),
        snap(g.now), g.elapsedMs.toString(), if (g.hintOn) "1" else "0", g.undo.joinToString(";") { snap(it) },
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 8 || f[0] != "ur1") return null
        SavedGame(
            config = GameConfig(Mode.valueOf(f[1]), f[2].toInt().coerceIn(1, 2), f[3].toInt().coerceIn(0, 1)),
            now = parseSnap(f[4]),
            elapsedMs = f[5].toLong(),
            hintOn = f[6] == "1",
            undo = if (f[7].isBlank()) emptyList() else f[7].split(";").map { parseSnap(it) },
        )
    }.getOrNull()
}
