package io.github.boomboom.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** BoomBoom's mark in the top bar, and the launcher icon drawn from its real resources. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class LogoScreenshots : ScreenTest() {
    @Test
    fun markInHomesTopBar() {
        showApp(services(PreviewMusicAPI(delays = false)))
        compose.onNodeWithContentDescription("BoomBoom").captureRoboImage("build/outputs/roborazzi/logo_top_bar.png")
        shot("logo_home")
    }

    @Test
    fun launcherIcon() {
        val context = compose.activity
        val icon = context.getDrawable(R.mipmap.ic_launcher)
        assertTrue("an adaptive icon", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull("with a monochrome layer for themed icons", icon.monochrome)
        val size = 384
        val circle = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        val squircle = Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.3f, size * 0.3f, Path.Direction.CW) }
        val square = Path().apply { addRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), Path.Direction.CW) }
        val shapes = listOf(
            "Circle" to render(icon.background, icon.foreground, circle, size),
            "Rounded" to render(icon.background, icon.foreground, squircle, size),
            "Square" to render(icon.background, icon.foreground, square, size),
            "Themed" to render(null, icon.monochrome!!, circle, size, tint = 0xFFFFE7A3.toInt(), ground = 0xFF3A3420.toInt()),
        )
        compose.setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2A2A))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                for (row in shapes.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        for ((name, bitmap) in row) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(bitmap.asImageBitmap(), name, Modifier.size(160.dp))
                                Text(name, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    // At the sizes a launcher shows it.
                    for (dp in listOf(48, 56, 72)) Image(shapes[0].second.asImageBitmap(), null, Modifier.size(dp.dp))
                }
            }
        }
        shot("launcher_icon")
    }

    /** One launcher's view of an adaptive icon: 108 units of layers, the middle 72 shown through [mask]. */
    private fun render(background: Drawable?, foreground: Drawable, mask: Path, size: Int, tint: Int? = null, ground: Int? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(mask)
        val bleed = size * 18 / 72
        if (ground != null) canvas.drawColor(ground)
        background?.apply {
            setBounds(-bleed, -bleed, size + bleed, size + bleed)
            draw(canvas)
        }
        foreground.mutate().apply {
            setBounds(-bleed, -bleed, size + bleed, size + bleed)
            if (tint != null) setTint(tint) else setTintList(null)
            if (tint != null) setTintMode(PorterDuff.Mode.SRC_IN)
            draw(canvas)
        }
        return bitmap
    }
}
