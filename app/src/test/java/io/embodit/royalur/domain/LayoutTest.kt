package io.embodit.royalur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Design v1.2 spatialLayout.occlusionRule + panelGeometryCheck (QA q1-1): eyes = the measured emulator reset (eye level
 * with the volume centre, 0.7 / 1.2 / 1.9 / 2.5 m, azimuth 0 / ±30°, about 4° down at 1.9 m) + the design's sit / stand /
 * sit ±30°; tilts 0 / 10 / 15°; targets = every square centre and the top of a piece standing on it.
 */
class LayoutTest {
    @Test fun noSquareCentreOrPieceTopIsHiddenFromAnyEye() {
        var rays = 0
        for (tilt in Settings.TILTS) {
            val l = Layout(tilt)
            for ((eyeName, eye) in l.eyes()) for (sq in Ur.SQUARES) {
                for ((tag, up) in listOf("square" to 0.0f, "piece top" to Layout.PIECE_H)) {
                    val target = l.toScene(l.square(sq) + V3(0f, up, 0f))
                    rays++
                    assertNull("$tilt° $eyeName -> ${sq.name} $tag", l.blockedBy(eye, target))
                }
            }
        }
        println("occlusion rays checked: $rays")
    }

    @Test fun measuredResetEyeLooksAlmostLevel() {
        val l = Layout(15)
        val eye = l.eyes().getValue("reset d=1.9 az=0")
        val front = l.toScene(V3(0f, 0f, Layout.BOX_D / 2))
        val deg = Math.toDegrees(kotlin.math.atan2((eye.y - front.y).toDouble(), (eye.z - front.z).toDouble()))
        assertTrue("elevation $deg", deg in 2.0..6.0)
    }

    @Test fun everythingFitsTheVolumeAndTheLowZoneRules() {
        for (tilt in Settings.TILTS) {
            val l = Layout(tilt)
            val vol = l.volume()
            for (o in l.obstacles()) {
                val pts = if (o.local) listOf(o.box.min, o.box.max, V3(o.box.min.x, o.box.max.y, o.box.min.z), V3(o.box.max.x, o.box.max.y, o.box.max.z)).map { l.toScene(it) } else listOf(o.box.min, o.box.max)
                for (p in pts) assertTrue("${o.name} $p outside the volume at $tilt°", vol.contains(p, 1e-3f))
                if (o.local) continue
                val top = o.box.max.y
                val frontZ = o.box.max.z - Layout.D_Z // design coordinates
                if (top > Layout.LOW_ZONE + 1e-4f) {
                    // rule 2: tall things only beside the board, front edge z <= +0.08 (design)
                    val inner = minOf(abs(o.box.min.x), abs(o.box.max.x))
                    assertTrue("${o.name} is tall but inside |x| 0.29 (inner $inner)", inner >= 0.29f - 0.0026f)
                    assertTrue("${o.name} front edge $frontZ > 0.08", frontZ <= 0.08f + 1e-3f)
                } else if (o.box.min.z - Layout.D_Z > 0.115f) {
                    // rule 1: everything in front of the board stays under 0.028
                    assertTrue("${o.name} top $top in the low zone", top <= Layout.LOW_ZONE + 1e-4f)
                }
            }
            // tags do not overlap each other or the tray
            val s = l.corners(l.seatCentre(), l.seatSize()); val d = l.corners(l.diceTagCentre(), l.diceTagSize())
            assertTrue(s.maxOf { it.x } < d.minOf { it.x })
            assertTrue(d.maxOf { it.x } < Layout.TRAY.x - Layout.TRAY_W / 2)
        }
    }

    @Test fun piecesStackOnTowersAndArcsPeakAboveTheChord() {
        val l = Layout(15)
        for (side in 0..1) for (i in 1 until 7) assertTrue(l.slot(side, false, i).at.y > l.slot(side, false, i - 1).at.y)
        assertTrue(!l.slot(Ur.NEAR, false, 0).tilted && l.slot(Ur.FAR, false, 0).tilted)
        val a = V3(0f, 0f, 0f); val b = V3(0.1f, 0f, 0f)
        assertEquals(Layout.CAPTURE_ARC, l.arc(a, b, 0.5f, Layout.CAPTURE_ARC).y, 1e-5f)
        // toLocal inverts toScene
        val p = V3(0.12f, 0.01f, -0.04f)
        val q = l.toLocal(l.toScene(p))
        assertTrue(abs(q.x - p.x) < 1e-5f && abs(q.y - p.y) < 1e-5f && abs(q.z - p.z) < 1e-5f)
    }
}
