// TV Remote core 0.2.1 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import java.nio.charset.StandardCharsets;

/**
 * Small QR code generator (ISO/IEC 18004), just what a web address needs: byte mode, error
 * correction level M, versions 1 to 6 (up to 106 bytes). The mask kept is the one with the best
 * score (penalty rules of the standard). Plain Java; tested on a PC (test/).
 */
public final class QrCode {
    /** Total codewords, error correction codewords per block and number of blocks, versions 1 to 6, level M. */
    private static final int[] TOTAL = {0, 26, 44, 70, 100, 134, 172};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};

    public final int size;
    private final boolean[][] dark, function;

    private QrCode(int version) {
        size = version * 4 + 17;
        dark = new boolean[size][size];
        function = new boolean[size][size];
    }

    /** Is the module at (x, y) dark? */
    public boolean get(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && dark[y][x];
    }

    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 6 && capacity(version) < data.length + 2) version++;
        if (version > 6) throw new IllegalArgumentException("Text too long for the QR code");

        // Bit stream: byte mode (0100), 8-bit length, data, terminator, padding
        int dataBytes = capacity(version);
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);
        bb.append(data.length, 8);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, dataBytes * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < dataBytes * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);

        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns(version);
        qr.drawCodewords(interleave(version, bb.bytes()));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            qr.applyMask(m);
            qr.drawFormat(m);
            long p = qr.penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
            qr.applyMask(m); // a mask is its own inverse
        }
        qr.applyMask(best);
        qr.drawFormat(best);
        return qr;
    }

    private static int capacity(int version) {
        return TOTAL[version] - EC_PER_BLOCK[version] * BLOCKS[version];
    }

    // ---------------------------------------------------------------- Error correction

    private static byte[] interleave(int version, byte[] data) {
        int blocks = BLOCKS[version], ec = EC_PER_BLOCK[version], per = data.length / blocks;
        byte[] divisor = rsDivisor(ec);
        byte[][] d = new byte[blocks][], e = new byte[blocks][];
        for (int i = 0; i < blocks; i++) {
            d[i] = new byte[per];
            System.arraycopy(data, i * per, d[i], 0, per);
            e[i] = rsRemainder(d[i], divisor);
        }
        byte[] out = new byte[TOTAL[version]];
        int k = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < blocks; b++) out[k++] = d[b][i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < blocks; b++) out[k++] = e[b][i];
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) mul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = mul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    /** Product in GF(2^8), polynomial 0x11D. */
    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------------------------------------------------------------- Drawing

    private void set(int x, int y, boolean on) {
        dark[y][x] = on;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version >= 2) {
            int c = size - 7;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    set(c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormat(0); // reserves the area, written again once the mask is chosen
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, dist != 2 && dist != 4);
            }
        }
    }

    /** Format information: level M (00) and mask, BCH(15,5) code, two copies. */
    private void drawFormat(int mask) {
        int data = mask; // level M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert) dark[y][x] = !dark[y][x];
            }
        }
    }

    /** Penalties of the standard: runs of one colour, 2×2 blocks, finder-like patterns, dark/light balance. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                StringBuilder line = new StringBuilder();
                for (int b = 0; b < size; b++) {
                    boolean v = pass == 0 ? dark[a][b] : dark[b][a];
                    line.append(v ? '1' : '0');
                    if (b > 0 && v == (pass == 0 ? dark[a][b - 1] : dark[b - 1][a])) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        run = 1;
                    }
                }
                String s = line.toString();
                for (int at = s.indexOf("1011101"); at >= 0; at = s.indexOf("1011101", at + 1)) {
                    boolean before = at >= 4 && s.startsWith("0000", at - 4);
                    boolean after = at + 11 <= s.length() && s.startsWith("0000", at + 7);
                    if (before || after) p += 40;
                }
            }
        }
        int black = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) black++;
                if (x + 1 < size && y + 1 < size && dark[y][x] == dark[y][x + 1]
                        && dark[y][x] == dark[y + 1][x] && dark[y][x] == dark[y + 1][x + 1]) p += 3;
            }
        }
        int total = size * size;
        p += (long) (Math.abs(black * 20 - total * 10) / total) * 10;
        return p;
    }

    private static final class BitBuffer {
        private byte[] buf = new byte[256];
        int length;

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) buf[length >>> 3] |= (byte) (0x80 >>> (length & 7));
                length++;
            }
        }

        byte[] bytes() {
            byte[] out = new byte[length / 8];
            System.arraycopy(buf, 0, out, 0, out.length);
            return out;
        }
    }
}
