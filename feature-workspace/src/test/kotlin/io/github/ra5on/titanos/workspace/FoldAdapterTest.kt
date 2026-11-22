package io.github.ra5on.titanos.workspace

import androidx.activity.ComponentActivity
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import io.github.ra5on.titanos.ui.Posture
import io.github.ra5on.titanos.ui.foldOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Was androidx.window meldet, wird zur Haltung (core-ui foldOf), geprüft mit window-testing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w841dp-h701dp-mdpi")
class FoldAdapterTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun feature(state: FoldingFeature.State, orientation: FoldingFeature.Orientation) =
        androidx.window.testing.layout.FoldingFeature(activity, state = state, orientation = orientation, size = 20)

    @Test
    fun halfOpenedHorizontalIsTabletop() {
        val fold = foldOf(TestWindowLayoutInfo(listOf(feature(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL))))
        assertEquals(Posture.Tabletop, fold.posture)
        assertEquals(20, fold.hinge.height)
    }

    @Test
    fun halfOpenedVerticalIsBook() {
        val fold = foldOf(TestWindowLayoutInfo(listOf(feature(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.VERTICAL))))
        assertEquals(Posture.Book, fold.posture)
        assertEquals(20, fold.hinge.width)
    }

    @Test
    fun openFlatStaysFlat() {
        assertEquals(Posture.Flat, foldOf(TestWindowLayoutInfo(listOf(feature(FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL)))).posture)
        assertEquals(Posture.Flat, foldOf(TestWindowLayoutInfo(emptyList())).posture)
    }
}
