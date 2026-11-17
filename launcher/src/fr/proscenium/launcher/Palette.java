package fr.proscenium.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** Extraction de couleurs : accent d'un fond d'écran, couleur dominante d'une bannière. */
final class Palette {
    private Palette() {}

    /** Couleur d'accent d'une image : moyenne pondérée par la saturation, puis ravivée. */
    static int accentOf(Bitmap small) {
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        return vivid(weightedAverage(px), 0xFF7FA7FF);
    }

    /** Couleur dominante d'une bannière d'appli, ou {@code fallback} si elle est grise. */
    static int dominant(Drawable d, int fallback) {
        try {
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, 32, 18);
            copy.draw(new Canvas(b));
            int[] px = new int[32 * 18];
            b.getPixels(px, 0, 32, 0, 0, 32, 18);
            b.recycle();
            return vivid(weightedAverage(px), fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Moyenne pondérée par saturation × luminosité ; 0 si l'image est presque grise. */
    private static int weightedAverage(int[] px) {
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, total = 0;
        for (int c : px) {
            if (Color.alpha(c) < 128) continue;
            Color.colorToHSV(c, hsv);
            double wgt = hsv[1] * hsv[2] * hsv[1];
            r += Color.red(c) * wgt;
            g += Color.green(c) * wgt;
            b += Color.blue(c) * wgt;
            total += wgt;
        }
        if (total < px.length * 0.01) return 0;
        return Color.rgb((int) (r / total), (int) (g / total), (int) (b / total));
    }

    private static int vivid(int c, int fallback) {
        if (c == 0) return fallback;
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.max(0.45f, Math.min(hsv[1] * 1.3f, 0.85f));
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }
}
