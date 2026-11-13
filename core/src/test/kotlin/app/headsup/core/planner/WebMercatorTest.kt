package app.headsup.core.planner

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

class WebMercatorTest {
    @Test fun theCentreOfTheWorldIsHalfwayAcrossAndDown() {
        assertEquals(128.0, WebMercator.x(0.0, 0.0), 1e-9)
        assertEquals(128.0, WebMercator.y(0.0, 0.0), 1e-9)
    }

    @Test fun londonIsInTheTileTheStreetMapNamesForIt() {
        // Zoom 10 tile of London (51.5074, −0.1278): x 511, y 340.
        val x = WebMercator.x(-0.1278, 10.0) / 256.0
        val y = WebMercator.y(51.5074, 10.0) / 256.0
        assertEquals(511, floor(x).toInt())
        assertEquals(340, floor(y).toInt())
    }

    @Test fun pixelsGoBackToTheSamePlace() {
        for ((lat, lon) in listOf(50.06 to 19.94, -33.9 to 151.2, 64.1 to -21.9)) {
            for (zoom in listOf(3.0, 12.4, 17.0)) {
                assertEquals(lat, WebMercator.lat(WebMercator.y(lat, zoom, 192.0), zoom, 192.0), 1e-7)
                assertEquals(lon, WebMercator.lon(WebMercator.x(lon, zoom, 192.0), zoom, 192.0), 1e-7)
            }
        }
    }

    @Test fun eachZoomDoublesTheWorld() {
        assertEquals(2 * WebMercator.x(10.0, 5.0), WebMercator.x(10.0, 6.0), 1e-6)
    }
}
