package dev.modmaker.core.noise;

/**
 * Faithful port of arc.util.noise.Simplex (only the parts planet meshes use: 3D multi-octave noise).
 * Ported verbatim so previews render the same terrain as the game for identical parameters - the
 * planet meshes call Simplex.noise3d(7 + seed, octaves, persistence, scale, 5 + x, 5 + y, 5 + z).
 */
public final class SimplexNoise {

    private static final int[][] GRAD3 = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };

    private SimplexNoise() {
    }

    /** 3D multi-octave simplex noise, same semantics as arc's Simplex.noise3d. */
    public static float noise3d(int seed, double octaves, double persistence, double scale,
        double x, double y, double z) {
        double total = 0;
        double frequency = scale;
        double amplitude = 1;
        double maxAmplitude = 0;

        for (int i = 0; i < octaves; i++) {
            total += (raw3d(seed, x * frequency, y * frequency, z * frequency) + 1f) / 2f * amplitude;

            frequency *= 2;
            maxAmplitude += amplitude;
            amplitude *= persistence;
        }

        return (float) (total / maxAmplitude);
    }

    /** 3D raw simplex noise, ported verbatim from arc's Simplex.raw3d. */
    public static double raw3d(int seed, double x, double y, double z) {
        double n0, n1, n2, n3;

        double f3 = 1.0 / 3.0;
        double s = (x + y + z) * f3;
        int i = fastfloor(x + s);
        int j = fastfloor(y + s);
        int k = fastfloor(z + s);

        double g3 = 1.0 / 6.0;
        double t = (i + j + k) * g3;
        double x0 = x - (i - t);
        double y0 = y - (j - t);
        double z0 = z - (k - t);

        int i1, j1, k1;
        int i2, j2, k2;

        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0;
                i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0;
                i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1;
                i2 = 1; j2 = 0; k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0; j1 = 0; k1 = 1;
                i2 = 0; j2 = 1; k2 = 1;
            } else if (x0 < z0) {
                i1 = 0; j1 = 1; k1 = 0;
                i2 = 0; j2 = 1; k2 = 1;
            } else {
                i1 = 0; j1 = 1; k1 = 0;
                i2 = 1; j2 = 1; k2 = 0;
            }
        }

        double x1 = x0 - i1 + g3;
        double y1 = y0 - j1 + g3;
        double z1 = z0 - k1 + g3;
        double x2 = x0 - i2 + 2.0 * g3;
        double y2 = y0 - j2 + 2.0 * g3;
        double z2 = z0 - k2 + 2.0 * g3;
        double x3 = x0 - 1.0 + 3.0 * g3;
        double y3 = y0 - 1.0 + 3.0 * g3;
        double z3 = z0 - 1.0 + 3.0 * g3;

        int ii = i & 255;
        int jj = j & 255;
        int kk = k & 255;
        int gi0 = perm(seed, ii + perm(seed, jj + perm(seed, kk))) % 12;
        int gi1 = perm(seed, ii + i1 + perm(seed, jj + j1 + perm(seed, kk + k1))) % 12;
        int gi2 = perm(seed, ii + i2 + perm(seed, jj + j2 + perm(seed, kk + k2))) % 12;
        int gi3 = perm(seed, ii + 1 + perm(seed, jj + 1 + perm(seed, kk + 1))) % 12;

        double t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
        if (t0 < 0) {
            n0 = 0.0;
        } else {
            t0 *= t0;
            n0 = t0 * t0 * dot(GRAD3[gi0], x0, y0, z0);
        }

        double t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
        if (t1 < 0) {
            n1 = 0.0;
        } else {
            t1 *= t1;
            n1 = t1 * t1 * dot(GRAD3[gi1], x1, y1, z1);
        }

        double t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
        if (t2 < 0) {
            n2 = 0.0;
        } else {
            t2 *= t2;
            n2 = t2 * t2 * dot(GRAD3[gi2], x2, y2, z2);
        }

        double t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
        if (t3 < 0) {
            n3 = 0.0;
        } else {
            t3 *= t3;
            n3 = t3 * t3 * dot(GRAD3[gi3], x3, y3, z3);
        }

        return 32.0 * (n0 + n1 + n2 + n3);
    }

    /** arc's seeded hash: seed (any) + x (masked to 0-255) -> 0-255. */
    static int perm(int seed, int x) {
        x = (x & 255) * 0x45d9f3b;
        x = ((x >>> 16) ^ x) * (0x45d9f3b + seed);
        x = (x >>> 16) ^ x;
        return x & 0xff;
    }

    static int fastfloor(double x) {
        return x > 0 ? (int) x : (int) x - 1;
    }

    static double dot(int[] g, double x, double y, double z) {
        return g[0] * x + g[1] * y + g[2] * z;
    }
}
