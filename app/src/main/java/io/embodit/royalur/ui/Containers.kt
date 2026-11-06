package io.embodit.royalur.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "RoyalBoard"
    const val CONTROL = "RoyalControl"
    const val CONTROL_TAG = "control"
}

enum class Page(val stateId: String) { PLAY("S03"), NEW("S02"), RULES("S13"), SETTINGS("S12") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var moreOpen by mutableStateOf(false)
    var exitConfirm by mutableStateOf(false)
    var newConfirm by mutableStateOf(false)
}
