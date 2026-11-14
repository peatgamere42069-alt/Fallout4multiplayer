package com.imageguard.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * On-device explicit-image classifier.
 *
 * Model: Yahoo open_nsfw (ResNet-50 "thin"), converted to TensorFlow Lite.
 * Input: 1x224x224x3 float, BGR channel order, per-channel mean subtracted.
 * Output: [safe, nsfw] probabilities.
 */
class NsfwClassifier(context: Context) {

    private val interpreter: Interpreter
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(4 * CROP * CROP * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }
    private val pixels = IntArray(CROP * CROP)

    init {
        val fd = context.assets.openFd(MODEL_FILE)
        val model = FileInputStream(fd.fileDescriptor).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    }

    /** Returns the probability (0..1) that the image is explicit, or null if it can't be decoded. */
    fun score(resolver: ContentResolver, uri: Uri): Float? {
        val bitmap = decode(resolver, uri) ?: return null
        return try {
            score(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun score(bitmap: Bitmap): Float {
        // Same preprocessing as the original model: squash to 256x256, centre-crop 224x224.
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val resized = Bitmap.createScaledBitmap(src, RESIZE, RESIZE, true)
        val offset = (RESIZE - CROP) / 2
        resized.getPixels(pixels, 0, CROP, offset, offset, CROP, CROP)
        if (resized !== src) resized.recycle()
        if (src !== bitmap) src.recycle()

        input.rewind()
        for (p in pixels) {
            input.putFloat(((p and 0xFF) - MEAN_B))
            input.putFloat((((p shr 8) and 0xFF) - MEAN_G))
            input.putFloat((((p shr 16) and 0xFF) - MEAN_R))
        }
        input.rewind()
        interpreter.run(input, output)
        return output[0][1]
    }

    companion object {
        private const val MODEL_FILE = "nsfw.tflite"
        private const val RESIZE = 256
        private const val CROP = 224
        private const val MEAN_B = 104f
        private const val MEAN_G = 117f
        private const val MEAN_R = 123f

        @Volatile private var instance: NsfwClassifier? = null

        fun get(context: Context): NsfwClassifier =
            instance ?: synchronized(this) {
                instance ?: NsfwClassifier(context.applicationContext).also { instance = it }
            }

        /** Decodes an image downsampled so its short side is roughly 256px (keeps memory low). */
        fun decode(resolver: ContentResolver, uri: Uri, targetShortSide: Int = RESIZE): Bitmap? =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(resolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                        val w = info.size.width
                        val h = info.size.height
                        val shortSide = min(w, h)
                        if (shortSide > targetShortSide) {
                            val scale = targetShortSide.toFloat() / shortSide
                            decoder.setTargetSize(
                                max(1, (w * scale).toInt()),
                                max(1, (h * scale).toInt())
                            )
                        }
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetShortSide) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                }
            } catch (t: Throwable) {
                null
            }
    }
}
