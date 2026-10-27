package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * The home screen photograph and its blurred copy. As in CleanFrame, the blur is made once per
 * photo rather than live: Fire OS has no live blur before Android 12, and a Fire TV Stick could
 * not afford one anyway. The glass dock and the scrolled-down background both draw the copy.
 */
final class Backdrop {
    private static final int BLUR_WIDTH = 240;

    private Backdrop() {}

    /** The photo for the current Background setting at about screen size, or null for a plain colour. */
    static Bitmap photo(Context c, Config cfg, int w, int h) {
        String mode = cfg.get(Config.BACKGROUND);
        if ("photo".equals(mode)) {
            String asset = "photos/" + cfg.get(Config.PHOTO) + ".jpg";
            try {
                BitmapFactory.Options o = bounds(c, asset);
                o.inSampleSize = sample(o, w, h);
                o.inJustDecodeBounds = false;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                try (InputStream in = c.getAssets().open(asset)) {
                    return BitmapFactory.decodeStream(in, null, o);
                }
            } catch (IOException e) {
                return null;
            }
        }
        if ("image".equals(mode)) {
            File f = cfg.wallpaper();
            if (f == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = sample(o, w, h);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        }
        return null;
    }

    private static BitmapFactory.Options bounds(Context c, String asset) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getAssets().open(asset)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        return o;
    }

    private static int sample(BitmapFactory.Options o, int w, int h) {
        int s = 1;
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        return s;
    }

    /**
     * A soft copy, 240 px wide: three box-blur passes each way approximate a Gaussian. Stretched
     * back over the screen with bilinear filtering it reads like CleanFrame's frosted glass.
     */
    static Bitmap blur(Bitmap src) {
        if (src == null || src.getWidth() == 0) return null;
        int w = BLUR_WIDTH, h = Math.max(1, Math.round(src.getHeight() * (float) BLUR_WIDTH / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 4, true);
            boxBlur(tmp, px, w, h, 4, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** One running-sum box blur of radius r, along rows ({@code horizontal}) or columns. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[index(line, Math.max(0, Math.min(len - 1, k)), w, horizontal)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[index(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int drop = in[index(line, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                sb += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
