package fr.proscenium.launcher;

import android.graphics.Bitmap;

/** Flou par boîtes successives (approxime un flou gaussien), sur une petite image. */
final class Blur {
    private Blur() {}

    static void box(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] a = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        box(a, w, h, radius, passes);
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    /** Floute en place un tableau de pixels ARGB opaques. */
    static void box(int[] a, int w, int h, int radius, int passes) {
        int[] b = new int[w * h];
        for (int p = 0; p < passes; p++) {
            pass(a, b, w, h, radius, true);
            pass(b, a, w, h, radius, false);
        }
    }

    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[idx(l, clamp(i, len), w, horizontal)];
                rs += (c >> 16) & 0xff; gs += (c >> 8) & 0xff; bs += c & 0xff;
            }
            for (int i = 0; i < len; i++) {
                dst[idx(l, i, w, horizontal)] = 0xff000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int out = src[idx(l, clamp(i - r, len), w, horizontal)];
                int in = src[idx(l, clamp(i + r + 1, len), w, horizontal)];
                rs += ((in >> 16) & 0xff) - ((out >> 16) & 0xff);
                gs += ((in >> 8) & 0xff) - ((out >> 8) & 0xff);
                bs += (in & 0xff) - (out & 0xff);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }
}
