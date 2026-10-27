package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;

/**
 * CleanFrame's glass dock: the slice of the blurred photo that lies behind it, a light veil and a
 * single top hairline that follows the radius. No outer shadow. The blurred photo is drawn as if
 * it covered {@code screen} (centre-crop), so the glass lines up with the photo wherever the dock
 * scrolls; call {@link #invalidate} when it moves.
 */
final class Glass extends FrameLayout {
    private final View screen;
    private final float radius;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Matrix matrix = new Matrix();
    private final int[] at = new int[2], origin = new int[2];
    private Bitmap soft;

    Glass(Context c, View screen) {
        super(c);
        this.screen = screen;
        radius = Ui.dp(c, 24);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        veil.setColor(0x29080E12);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, Ui.dp(c, 0.75f)));
        edge.setColor(0x3DFFFFFF);
    }

    /** The blurred photo, or null on a plain-colour background (the veil alone then). */
    void setSoft(Bitmap b) {
        soft = b;
        veil.setColor(b == null ? 0x14FFFFFF : 0x29080E12);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        if (soft != null && screen.getWidth() > 0) {
            getLocationInWindow(at);
            screen.getLocationInWindow(origin);
            float sw = screen.getWidth(), sh = screen.getHeight();
            float scale = Math.max(sw / soft.getWidth(), sh / soft.getHeight());
            float dx = (sw - soft.getWidth() * scale) / 2 - (at[0] - origin[0]);
            float dy = (sh - soft.getHeight() * scale) / 2 - (at[1] - origin[1]);
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);
            canvas.drawBitmap(soft, matrix, image);
        }
        canvas.drawRect(box, veil);
        canvas.restore();
        // The hairline shows along the top only: clip it to the upper part of the outline.
        canvas.save();
        canvas.clipRect(0, 0, box.right, radius);
        float half = edge.getStrokeWidth() / 2;
        canvas.drawRoundRect(half, half, box.right - half, box.bottom - half, radius, radius, edge);
        canvas.restore();
    }
}
