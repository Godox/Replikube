package fr.godox.replikube.core.render

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette
import kotlin.math.sqrt
import kotlin.test.Test

class DebugPrintTest {
    @Test
    fun show() {
        val size = GridSize(7, 7, 7)
        val sphere = VoxelGrid.build(size) { x, y, z ->
            if (sqrt((x * x + y * y + z * z).toDouble()) <= 2.6) Palette.CYAN else Palette.EMPTY
        }
        println("SPHERE\n" + AsciiRenderer.render(sphere))
        println("LEGEND " + paletteLegend(sphere))

        val layers = VoxelGrid.build(size) { x, y, _ ->
            when (y) { 3 -> Palette.RED; 2 -> Palette.YELLOW; 1 -> Palette.GREEN; else -> Palette.EMPTY }
        }
        println("LAYERS\n" + AsciiRenderer.render(layers))
        println("LEGEND " + paletteLegend(layers))
    }
}
