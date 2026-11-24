package com.boldframe.boldcut

import android.graphics.Matrix
import android.view.TextureView
import androidx.media3.ui.PlayerView
import kotlin.math.tan

/** Frame ke center ko origin maan kar transform (frame pixels me, y neeche ki taraf). */
fun Transform.pixelMatrix(w: Float, h: Float): Matrix {
    val m = Matrix()
    m.postScale(scaleX, scaleY)
    if (skewX != 0f || skewY != 0f) {
        m.postSkew(
            tan(Math.toRadians(skewX.toDouble())).toFloat(),
            tan(Math.toRadians(skewY.toDouble())).toFloat()
        )
    }
    m.postRotate(rotation)
    m.postTranslate(x * w, y * h)
    return m
}

/** TextureView (view pixels, top-left origin) ke liye. */
fun Transform.viewMatrix(w: Float, h: Float): Matrix {
    val m = Matrix()
    m.postTranslate(-w / 2f, -h / 2f)
    m.postConcat(pixelMatrix(w, h))
    m.postTranslate(w / 2f, h / 2f)
    return m
}

/** Export (Media3 MatrixTransformation) ke liye: NDC (-1..1, y upar) coordinates. */
fun Transform.ndcMatrix(w: Float, h: Float): Matrix {
    val n = Matrix()
    n.setScale(2f / w, -2f / h)
    val ni = Matrix()
    n.invert(ni)
    val out = Matrix()
    out.set(ni)
    out.postConcat(pixelMatrix(w, h))
    out.postConcat(n)
    return out
}

/** Preview player (TextureView) pe transform lagao. */
fun applyPreviewTransform(view: PlayerView, tf: Transform) {
    val tv = view.videoSurfaceView as? TextureView ?: return
    if (tv.width == 0 || tv.height == 0) return
    tv.setTransform(if (tf.isIdentity) null else tf.viewMatrix(tv.width.toFloat(), tv.height.toFloat()))
}
