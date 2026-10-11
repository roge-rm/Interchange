package com.rm.interchange.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rm.interchange.sim.Picture
import com.rm.interchange.sim.Pictures
import java.io.ByteArrayOutputStream

/** PNG through Android's Bitmap. Pixels go in and out not multiplied by alpha. */
object AndroidPictures : Pictures {
    override fun decode(png: ByteArray): Picture {
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val b = BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: throw IllegalArgumentException("not a picture")
        val p = Picture(b.width, b.height)
        b.getPixels(p.argb, 0, b.width, 0, 0, b.width, b.height)
        b.recycle()
        return p
    }

    override fun encode(picture: Picture): ByteArray {
        val b = Bitmap.createBitmap(picture.argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }
}
