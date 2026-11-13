package app.headsup.core.planner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArTest {
    // The phone held upright with the camera level, looking north: x = east, y = up, z = south.
    private val north = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)

    @Test fun theCameraLooksNorthAtTheHorizon() {
        val d = Ar.cameraDirection(north)
        assertEquals(0.0, d.azimuth, 1e-9)
        assertEquals(0.0, d.altitude, 1e-9)
    }

    @Test fun whatTheCameraLooksAtIsInTheMiddle() {
        val p = Ar.project(north, 0.0, 0.0, 1000.0, 1080.0, 2400.0)
        assertTrue(p.front)
        assertEquals(540.0, p.x, 1e-6)
        assertEquals(1200.0, p.y, 1e-6)
    }

    @Test fun eastOfTheViewIsToTheRightAndAboveIsUp() {
        val right = Ar.project(north, 10.0, 0.0, 1000.0, 1080.0, 2400.0)
        assertTrue(right.x > 540.0)
        assertEquals(540.0 + 1000.0 * Math.tan(Math.toRadians(10.0)), right.x, 1e-6)
        val up = Ar.project(north, 0.0, 10.0, 1000.0, 1080.0, 2400.0)
        assertTrue(up.y < 1200.0)
    }

    @Test fun whatIsBehindIsNotInFront() {
        assertFalse(Ar.project(north, 180.0, 0.0, 1000.0, 1080.0, 2400.0).front)
    }

    @Test fun tiltingTheCameraUpMovesTheHorizonDown() {
        // Turned up 30°: the camera now looks at altitude 30°; device z gets an up part.
        val a = Math.toRadians(30.0)
        val r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -Math.sin(a), -Math.cos(a), 0.0, Math.cos(a), -Math.sin(a))
        val look = Ar.cameraDirection(r)
        assertEquals(0.0, look.azimuth, 1e-6)
        assertEquals(30.0, look.altitude, 1e-6)
        assertTrue(Ar.project(r, 0.0, 0.0, 1000.0, 1080.0, 2400.0).y > 1200.0, "the horizon sits below the middle")
        val centre = Ar.project(r, 0.0, 30.0, 1000.0, 1080.0, 2400.0)
        assertEquals(1200.0, centre.y, 1e-6)
    }

    @Test fun declinationTurnsTrueToMagnetic() {
        assertEquals(350.0, Ar.magneticAzimuth(360.0, 10.0), 1e-9)
    }

    @Test fun focalLengthFollowsTheSideThatFitsTheScreen() {
        // A tall screen is fitted by the picture's long side: focal = height / (2 tan(fov/2)).
        val f = Ar.focalPx(70.0, 0.75, 1080.0, 2400.0)
        assertEquals(2400.0 / (2 * Math.tan(Math.toRadians(35.0))), f, 1e-6)
        // A phone's main camera: a 6.4 mm side behind a 5 mm lens sees about 65°.
        assertTrue(abs(Ar.fieldOfView(6.4, 5.0) - 65.0) < 1.0)
    }
}
