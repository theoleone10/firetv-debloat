package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

/**
 * Dissolves rows that scroll up under the clock into the background, like CleanFrame's masked
 * list. It paints the same blurred photo and veil the screen shows when scrolled, fully opaque
 * at the top edge and fading to nothing below, over the page. Android's own fading edge did
 * not take effect on Fire OS, hence this.
 */
final class HeaderFade extends View {
    private final View screen;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mask = new Paint();
    private final Matrix matrix = new Matrix();
    private Bitmap soft;
    private int veil, ground;

    HeaderFade(Context c, View screen) {
        super(c);
        this.screen = screen;
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /** The blurred photo (null on a plain colour), the veil over it, and the plain colour. */
    void set(Bitmap soft, int veil, int ground) {
        this.soft = soft;
        this.veil = veil;
        this.ground = ground;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mask.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0xFF000000, 0xFF000000, 0x00000000}, new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int layer = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawColor(ground);
        if (soft != null && screen.getWidth() > 0) {
            float sw = screen.getWidth(), sh = screen.getHeight();
            float scale = Math.max(sw / soft.getWidth(), sh / soft.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((sw - soft.getWidth() * scale) / 2, (sh - soft.getHeight() * scale) / 2);
            canvas.drawBitmap(soft, matrix, image);
        }
        canvas.drawColor(veil);
        canvas.drawRect(0, 0, w, h, mask);
        canvas.restoreToCount(layer);
    }
}
