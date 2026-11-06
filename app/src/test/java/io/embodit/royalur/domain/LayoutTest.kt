package io.embodit.royalur.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design spatialLayout.panelGeometryCheck: 2 tags x model boxes x eyes x 3 tilts (D35 / D20). */
class LayoutTest {
    @Test fun tagsStayInTheVolumeOutOfEveryModelAndDoNotHideAnySquare() {
        for (tilt in Settings.TILTS) {
            val l = Layout(tilt)
            val vol = l.volume()
            val boxes = l.modelBoxes()
            val tags = mapOf("seat" to l.corners(l.seatCentre(), l.seatSize()), "dice" to l.corners(l.diceTagCentre(), l.diceTagSize()))
            for ((name, cs) in tags) for (c in cs) {
                assertTrue("$name corner $c outside volume at $tilt°", vol.contains(c, 1e-4f))
                for ((b, box) in boxes) assertFalse("$name corner in $b at $tilt°", box.contains(c))
            }
            // every model inside the volume
            for ((b, box) in boxes) {
                assertTrue("$b min ${box.min} at $tilt°", vol.contains(box.min, 1e-3f))
                assertTrue("$b max ${box.max} at $tilt°", vol.contains(box.max, 1e-3f))
            }
            // eye -> every square centre misses both tags (tag rectangles as thin boxes)
            val tagBoxes = tags.mapValues { (_, cs) -> Box3.of(cs.map { it + V3(0f, 0f, -0.001f) } + cs.map { it + V3(0f, 0f, 0.001f) }) }
            for ((e, eye) in l.eyes()) for (sq in Ur.SQUARES) {
                val target = l.toScene(l.square(sq))
                for ((t, tb) in tagBoxes) assertFalse("$e -> ${sq.name} through $t at $tilt°", tb.hitBySegment(eye, target))
                assertFalse("$e -> ${sq.name} through the cup at $tilt°", boxes.getValue("cup").hitBySegment(eye, target))
            }
            // tags do not overlap each other
            val s = tags.getValue("seat"); val d = tags.getValue("dice")
            assertTrue(s.maxOf { it.x } < d.minOf { it.x })
            // the dice tag stays off the tray
            assertTrue(d.maxOf { it.x } < -Layout.TRAY_W / 2)
        }
    }

    @Test fun piecesStackOnTowersAndArcsPeakAboveTheChord() {
        val l = Layout(15)
        for (side in 0..1) for (i in 1 until 7) assertTrue(l.slot(side, false, i).y > l.slot(side, false, i - 1).y)
        val a = V3(0f, 0f, 0f); val b = V3(0.1f, 0f, 0f)
        assertTrue(kotlin.math.abs(l.arc(a, b, 0.5f, Layout.CAPTURE_ARC).y - Layout.CAPTURE_ARC) < 1e-5f)
    }
}
