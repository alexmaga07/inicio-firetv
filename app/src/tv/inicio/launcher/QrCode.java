package tv.inicio.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Generador QR mínimo (modo byte, corrección L, versiones 1–6). Suficiente para una URL corta. */
public class QrCode {
    static final int[] ECC_PER_BLOCK = {0, 7, 10, 15, 20, 26, 18};
    static final int[] BLOCKS = {0, 1, 1, 1, 1, 1, 2};
    static final int[] TOTAL = {0, 26, 44, 70, 100, 134, 172};

    final int size;
    final boolean[][] mod;
    final boolean[][] fn;

    QrCode(int version) {
        size = 17 + 4 * version;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
    }

    static boolean[][] encode(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { data = text.getBytes(); }
        int ver = 0;
        for (int v = 1; v <= 6; v++) {
            int cap = TOTAL[v] - ECC_PER_BLOCK[v] * BLOCKS[v];
            if (4 + 8 + data.length * 8 <= cap * 8) { ver = v; break; }
        }
        if (ver == 0) return null;
        QrCode q = new QrCode(ver);
        int dataCw = TOTAL[ver] - ECC_PER_BLOCK[ver] * BLOCKS[ver];

        // Bits: modo byte (0100), longitud (8 bits), datos, terminador y relleno
        int[] bits = new int[dataCw * 8];
        int n = 0;
        n = put(bits, n, 0x4, 4);
        n = put(bits, n, data.length, 8);
        for (byte b : data) n = put(bits, n, b & 0xFF, 8);
        n = Math.min(n + 4, dataCw * 8);
        while (n % 8 != 0) n++;
        byte[] cw = new byte[dataCw];
        for (int i = 0; i < n / 8; i++) {
            int v = 0;
            for (int j = 0; j < 8; j++) v = (v << 1) | bits[i * 8 + j];
            cw[i] = (byte) v;
        }
        for (int i = n / 8, k = 0; i < dataCw; i++, k++) cw[i] = (byte) (k % 2 == 0 ? 0xEC : 0x11);

        // Bloques + Reed-Solomon, intercalados
        int nb = BLOCKS[ver], ecc = ECC_PER_BLOCK[ver], blen = dataCw / nb;
        byte[] div = rsDivisor(ecc);
        byte[][] blocks = new byte[nb][];
        byte[][] eccs = new byte[nb][];
        for (int b = 0; b < nb; b++) {
            blocks[b] = new byte[blen];
            System.arraycopy(cw, b * blen, blocks[b], 0, blen);
            eccs[b] = rsRemainder(blocks[b], div);
        }
        byte[] all = new byte[TOTAL[ver]];
        int k = 0;
        for (int i = 0; i < blen; i++) for (int b = 0; b < nb; b++) all[k++] = blocks[b][i];
        for (int i = 0; i < ecc; i++) for (int b = 0; b < nb; b++) all[k++] = eccs[b][i];

        q.drawFunction(ver);
        q.drawCodewords(all);
        int best = 0;
        long bestPen = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            q.applyMask(m);
            q.drawFormat(m);
            long p = q.penalty();
            if (p < bestPen) { bestPen = p; best = m; }
            q.applyMask(m);
        }
        q.applyMask(best);
        q.drawFormat(best);
        return q.mod;
    }

    static int put(int[] bits, int n, int val, int len) {
        for (int i = len - 1; i >= 0; i--) bits[n++] = (val >>> i) & 1;
        return n;
    }

    void set(int x, int y, boolean dark) { mod[y][x] = dark; fn[y][x] = true; }

    void drawFunction(int ver) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        if (ver >= 2) {
            int p = 4 * ver + 10;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    set(p + dx, p + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormat(0);
    }

    void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, d != 2 && d != 4);
            }
        }
    }

    void drawFormat(int mask) {
        int data = (1 << 3) | mask;   // corrección L = 01
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (fn[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv) mod[y][x] = !mod[y][x];
            }
        }
    }

    /** Penalización simplificada (rachas, bloques 2x2 y equilibrio). */
    long penalty() {
        long p = 0;
        int dark = 0;
        for (int a = 0; a < size; a++) {
            int runR = 1, runC = 1;
            for (int b = 1; b < size; b++) {
                if (mod[a][b] == mod[a][b - 1]) { runR++; if (runR == 5) p += 3; else if (runR > 5) p++; } else runR = 1;
                if (mod[b][a] == mod[b - 1][a]) { runC++; if (runC == 5) p += 3; else if (runC > 5) p++; } else runC = 1;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (mod[y][x]) dark++;
                if (x < size - 1 && y < size - 1) {
                    boolean c = mod[y][x];
                    if (c == mod[y][x + 1] && c == mod[y + 1][x] && c == mod[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        int kk = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += Math.max(0, kk) * 10;
        return p;
    }

    static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, f);
        }
        return r;
    }

    static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    /** Imagen lista para mostrar: módulos oscuros sobre blanco, con margen de 4 módulos. */
    static Bitmap bitmap(String text, int px) {
        boolean[][] m = encode(text);
        if (m == null) return null;
        int n = m.length + 8;
        int cell = Math.max(1, px / n);
        int side = cell * n;
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.RGB_565);
        bmp.eraseColor(Color.WHITE);
        int ink = 0xFF1C0C1E;
        for (int y = 0; y < m.length; y++)
            for (int x = 0; x < m.length; x++)
                if (m[y][x])
                    for (int dy = 0; dy < cell; dy++)
                        for (int dx = 0; dx < cell; dx++)
                            bmp.setPixel((x + 4) * cell + dx, (y + 4) * cell + dy, ink);
        return bmp;
    }
}
