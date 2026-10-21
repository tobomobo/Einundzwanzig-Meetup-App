package space.einundzwanzig.meetup.core.ui.badge

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** Vectors from spec 03 §10.4, computed with the Dart `BadgeArtPainter` algorithm. */
class BadgeArtSpecTest {

    private val seed = "Aschaffenburg:875000"
    private val spec = BadgeArtSpec.from(seed)

    @Test
    fun `seed hash matches the spec vector`() {
        val hex = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("1301d904d84e84964275b1b51b87b762e69c82b0645e30c2f012f3d2ec7002c8", hex)
        assertEquals(seed, BadgeArtSpec.seedFor("Aschaffenburg", 875_000))
    }

    @Test
    fun `background colours`() {
        assertEquals(
            listOf(
                ArtColor(133, 30, 43, 0.3),
                ArtColor(126, 105, 16, 0.15),
                ArtColor(190, 82, 13, 0.25),
            ),
            spec.background,
        )
    }

    @Test
    fun `shape count and grid size`() {
        assertEquals(8, spec.shapes.size)
        assertEquals(14, spec.gridSize)
    }

    @Test
    fun `shapes table`() {
        data class Row(
            val type: BadgeArtShapeType,
            val cx: Double,
            val cy: Double,
            val k: Double,
            val color: ArtColor,
            val stroke: Boolean,
        )
        val expected = listOf(
            Row(BadgeArtShapeType.Rhombus, 0.694118, 0.709804, 0.105882, ArtColor(212, 93, 5, 0.15000000000000002), false),
            Row(BadgeArtShapeType.Lines, 0.529412, 0.717647, 0.384314, ArtColor(191, 93, 20, 0.06), true),
            Row(BadgeArtShapeType.Hexagon, 0.901961, 0.611765, 0.509804, ArtColor(239, 84, 26, 0.08), true),
            Row(BadgeArtShapeType.Circle, 0.690196, 0.392157, 0.368627, ArtColor(212, 64, 19, 0.12), false),
            Row(BadgeArtShapeType.Circle, 0.188235, 0.760784, 0.941176, ArtColor(148, 97, 48, 0.08), false),
            Row(BadgeArtShapeType.Hexagon, 0.070588, 0.952941, 0.823529, ArtColor(133, 114, 42, 0.08), true),
            Row(BadgeArtShapeType.Circle, 0.925490, 0.439216, 0.007843, ArtColor(242, 69, 0, 0.1), false),
            Row(BadgeArtShapeType.Circle, 0.784314, 0.074510, 0.003922, ArtColor(224, 36, 0, 0.09), false),
        )
        expected.forEachIndexed { i, row ->
            val shape = spec.shapes[i]
            assertEquals("type $i", row.type, shape.type)
            assertEquals("cx $i", row.cx, shape.cxFraction, 5e-7)
            assertEquals("cy $i", row.cy, shape.cyFraction, 5e-7)
            assertEquals("k $i", row.k, shape.radiusFraction, 5e-7)
            assertEquals("colour $i", row.color, shape.color)
            if (row.type != BadgeArtShapeType.Lines) assertEquals("stroke $i", row.stroke, shape.stroke)
        }
    }

    @Test
    fun `alpha is the exact Dart double`() {
        // 0.08 + 7 * 0.01 in IEEE doubles, used as is.
        assertEquals(0.15000000000000002, spec.shapes[0].color.alpha, 0.0)
        assertEquals(0.08 + 7 * 0.01, spec.shapes[0].color.alpha, 0.0)
    }

    @Test
    fun `radius uses w times 0_35 plus 10`() {
        val shape = spec.shapes[0]
        assertEquals(10 + (27.0 / 255.0) * (360 * 0.35), shape.radius(360.0), 1e-9)
    }

    @Test
    fun `colour rounding is half away from zero`() {
        // byte | r, g, b  (spec 03 §10.4)
        val cases = mapOf(
            0 to Triple(124, 30, 0),
            1 to Triple(124, 30, 0),
            2 to Triple(125, 30, 0), // banker's rounding would give 124
            3 to Triple(125, 30, 1),
            254 to Triple(251, 118, 51),
            255 to Triple(251, 119, 51),
        )
        cases.forEach { (byte, rgb) ->
            val c = BadgeArtSpec.channelColor(byte, byte, byte, 1.0)
            assertEquals("byte $byte", rgb, Triple(c.red, c.green, c.blue))
        }
    }

    @Test
    fun `hash bytes are read unsigned and modulo the length`() {
        val unsigned = BadgeArtSpec.fromHash(ByteArray(32) { (it * 8 - 128).toByte() })
        // byte(9) = -56 -> 200 unsigned -> 5 + 200 % 6 = 7 shapes; byte(30) = 112 -> grid 12
        assertEquals(7, unsigned.shapes.size)
        assertEquals(12, unsigned.gridSize)

        val wrapping = BadgeArtSpec.fromHash(ByteArray(32) { if (it == 9) 5 else it.toByte() })
        assertEquals(10, wrapping.shapes.size)
        val last = wrapping.shapes.last() // idx 37..39 -> bytes 5, 6, 7
        assertEquals(BadgeArtShapeType.Rhombus, last.type)
        assertEquals(5 / 255.0, last.cxFraction, 0.0)
        assertEquals(6 / 255.0, last.cyFraction, 0.0)
        assertEquals(7 / 255.0, last.radiusFraction, 0.0)
        assertEquals(true, last.stroke) // 6 % 3 == 0
    }
}
