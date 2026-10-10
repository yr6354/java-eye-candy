package candy;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A spiral galaxy you can spin: particles ride a galactic rotation curve,
 * arms are seeded as a logarithmic density wave, and everything is drawn with
 * additive Gaussian splats plus a cheap bloom. Exports a looping GIF.
 *
 * 旋涡星系模拟：粒子沿星系旋转曲线运动，旋臂用对数螺线密度波生成，
 * 渲染方式是加性高斯光斑 + 泛光，最后导出循环 GIF。
 */
public final class GalaxySim {

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args).help("""
                GalaxySim - spinning spiral galaxy renderer (pure JDK)

                  java src/candy/GalaxySim.java [options]

                  -w, -h <px>      output size (default 480x270)
                  --stars <n>      particle count (default 34000)
                  --frames <n>     animation frames (default 96)
                  --delay <ms>     frame delay (default 60)
                  --spin <deg>     rotation over the whole loop (default 40)
                  --tilt <deg>     disk inclination, 0 = face on (default 58)
                  --exposure <x>   brightness (default 1.0)
                  --seed <n>       random seed (default 20261011)
                  --out <file>     output gif (default docs/galaxy.gif)
                  --still <file>   also save one high-res frame as png
                  --still-size <px>  width of that still (default 1600)
                """);

        int w = Math.max(80, cli.i("w", 480));
        int h = Math.max(60, cli.i("h", w * 9 / 16));
        int stars = cli.i("stars", 34000);
        int frames = Math.max(2, cli.i("frames", 96));
        int delay = cli.i("delay", 60);
        double spin = Math.toRadians(cli.d("spin", 40));
        double tilt = Math.toRadians(cli.d("tilt", 58));
        double exposure = cli.d("exposure", 1.0);
        long seed = (long) cli.d("seed", 20261011);
        File out = new File(cli.s("out", "docs/galaxy.gif"));
        File still = cli.s("still", null) == null ? null : new File(cli.s("still", ""));
        int stillW = cli.i("still-size", 1600);

        Galaxy galaxy = new Galaxy(stars, seed);
        List<BufferedImage> gifFrames = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            double t = spin * f / frames; // radians at r = 1
            int[] px = galaxy.render(w, h, t, tilt, exposure, false);
            BufferedImage frame = Gif.frame(w, h);
            Gif.blit(frame, px, w, h);
            gifFrames.add(frame);
            if (f % 16 == 0) {
                System.out.printf("  frame %3d/%d%n", f + 1, frames);
            }
        }
        Gif.writeAnimated(out, gifFrames, delay);
        System.out.printf("galaxy   %d stars  %dx%d  %d frames%n", stars, w, h, frames);
        System.out.printf("wrote    %s  (%.2f MB, %.1fs)%n",
                out, out.length() / 1048576.0, (System.nanoTime() - t0) / 1e9);

        if (still != null) {
            int sh = (int) Math.round(stillW * h / (double) w);
            Galaxy dense = new Galaxy(stars * 4, seed + 7);
            int[] px = dense.render(stillW, sh, spin * 0.35, tilt, exposure * 1.05, true);
            Gif.writePng(still, px, stillW, sh);
            System.out.printf("wrote    %s  (%dx%d, %.2f MB)%n",
                    still, stillW, sh, still.length() / 1048576.0);
        }
    }

    /** The whole galaxy: static particle catalogue plus the rotation maths. */
    static final class Galaxy {
        final int n;
        final double[] r;
        final double[] theta;
        final double[] z;
        final float[] cr;
        final float[] cg;
        final float[] cb;
        final float[] lum;
        final float[] sigma;
        final double[] bgX;
        final double[] bgY;
        final float[] bgLum;

        /** flat-ish rotation curve: v(r) = v0 r / sqrt(r^2 + rc^2) */
        private static final double CORE = 0.38;
        private static final double PITCH = 4.3; // 1/tan(pitch angle) of the log spiral

        Galaxy(int n, long seed) {
            this.n = n;
            r = new double[n];
            theta = new double[n];
            z = new double[n];
            cr = new float[n];
            cg = new float[n];
            cb = new float[n];
            lum = new float[n];
            sigma = new float[n];
            Random rnd = new Random(seed);

            int bulge = (int) (n * 0.13);
            for (int i = 0; i < n; i++) {
                boolean isBulge = i < bulge;
                double radius;
                double ang;
                if (isBulge) {
                    radius = 0.20 * Math.pow(rnd.nextDouble(), 1.9);
                    ang = rnd.nextDouble() * Math.PI * 2;
                } else {
                    // exponential-ish disk, truncated at the visible edge
                    radius = 0.10 + 0.95 * Math.pow(rnd.nextDouble(), 0.85);
                    boolean onArm = rnd.nextDouble() < 0.66;
                    double arm = rnd.nextDouble() < 0.5 ? 0 : Math.PI;
                    ang = arm + PITCH * Math.log(radius / 0.075);
                    if (onArm) {
                        ang += gauss(rnd) * 0.26;
                    } else {
                        ang = rnd.nextDouble() * Math.PI * 2;
                    }
                }
                r[i] = radius;
                theta[i] = ang;
                z[i] = gauss(rnd) * (isBulge ? 0.115 : 0.022);

                // colour: warm core -> white disk -> blue rim
                double t = Math.min(1, radius / 1.05);
                double[] c = mixRgb(new double[]{1.00, 0.55, 0.16},
                        new double[]{1.00, 0.97, 0.92}, smoothstep(0.04, 0.28, t));
                c = mixRgb(c, new double[]{0.32, 0.52, 1.00}, smoothstep(0.34, 0.82, t));
                if (!isBulge && rnd.nextDouble() < 0.017) { // star forming knots
                    c = new double[]{1.0, 0.42, 0.52};
                } else if (!isBulge && rnd.nextDouble() < 0.013) {
                    c = new double[]{0.42, 0.95, 1.0};
                }
                cr[i] = (float) c[0];
                cg[i] = (float) c[1];
                cb[i] = (float) c[2];
                lum[i] = (float) (Math.pow(rnd.nextDouble(), 2.6) * (isBulge ? 0.24 : 0.46) + 0.005);
                sigma[i] = (float) (0.85 + 2.4 * Math.pow(rnd.nextDouble(), 8) * (isBulge ? 0.4 : 1));
            }

            int bg = 900;
            bgX = new double[bg];
            bgY = new double[bg];
            bgLum = new float[bg];
            Random brnd = new Random(seed + 991);
            for (int i = 0; i < bg; i++) {
                bgX[i] = brnd.nextDouble() * 2 - 1;
                bgY[i] = brnd.nextDouble() * 2 - 1;
                bgLum[i] = (float) (Math.pow(brnd.nextDouble(), 3) * 0.20);
            }
        }

        double omega(double radius) {
            return Math.sqrt(1 + CORE * CORE) / Math.sqrt(radius * radius + CORE * CORE);
        }

        /**
         * Render one frame into ARGB pixels.
         *
         * @param t rotation at r = 1, in radians
         */
        int[] render(int w, int h, double t, double tilt, double exposure, boolean dense) {
            double[] acc = new double[w * h * 3];
            double scale = 0.47 * Math.min(w, h) * 1.05;
            double cosI = Math.cos(tilt);
            double sinI = Math.sin(tilt);
            double pa = Math.toRadians(dense ? 24 : 18);
            double cosPa = Math.cos(pa);
            double sinPa = Math.sin(pa);
            double cx = w * 0.5;
            double cy = h * 0.5;

            // background stars (fixed in screen space)
            for (int i = 0; i < bgX.length; i++) {
                double sx = (bgX[i] * 0.5 + 0.5) * w;
                double sy = (bgY[i] * 0.5 + 0.5) * h;
                if (bgLum[i] > 0.02) {
                    splat(acc, w, h, sx, sy, 0.8, bgLum[i], 0.85f, 0.90f, 1f);
                }
            }

            for (int i = 0; i < n; i++) {
                double radius = r[i];
                // rotation curve: each particle advances by omega(r) * t / omega(1)
                double ang = theta[i] + omega(radius) * t;
                double px = radius * Math.cos(ang);
                double py = radius * Math.sin(ang);
                double x1 = px * cosPa - py * sinPa;
                double y1 = px * sinPa + py * cosPa;
                double sx = cx + x1 * scale;
                double sy = cy + y1 * scale * cosI + z[i] * scale * sinI;
                splat(acc, w, h, sx, sy, sigma[i], lum[i], cr[i], cg[i], cb[i]);
            }

            bloom(acc, w, h);
            return tonemap(acc, w, h, exposure);
        }

        /** Additive Gaussian point spread, the bread and butter of glow rendering. */
        private static void splat(double[] acc, int w, int h, double sx, double sy,
                                  double sigma, float lum, float r, float g, float b) {
            int k = (int) Math.ceil(sigma * 2.2);
            int x0 = (int) Math.floor(sx);
            int y0 = (int) Math.floor(sy);
            if (x0 < -k || y0 < -k || x0 >= w + k || y0 >= h + k || lum <= 0) {
                return;
            }
            double inv = 1 / (2 * sigma * sigma);
            for (int dy = -k; dy <= k; dy++) {
                int y = y0 + dy;
                if (y < 0 || y >= h) {
                    continue;
                }
                for (int dx = -k; dx <= k; dx++) {
                    int x = x0 + dx;
                    if (x < 0 || x >= w) {
                        continue;
                    }
                    double wgt = Math.exp(-(dx * dx + dy * dy) * inv) * lum;
                    int o = (y * w + x) * 3;
                    acc[o] += wgt * r;
                    acc[o + 1] += wgt * g;
                    acc[o + 2] += wgt * b;
                }
            }
        }

        /** Cheap two-pass box blur of the bright parts, added back on top. */
        private static void bloom(double[] acc, int w, int h) {
            double[] bright = new double[w * h * 3];
            for (int i = 0; i < bright.length; i++) {
                double v = acc[i] - 0.22;
                bright[i] = v > 0 ? v * 0.75 : 0;
            }
            blur(bright, w, h, 3);
            blur(bright, w, h, 7);
            for (int i = 0; i < acc.length; i++) {
                acc[i] += bright[i] * 0.55;
            }
        }

        private static void blur(double[] buf, int w, int h, int radius) {
            double[] tmp = new double[buf.length];
            double norm = 1.0 / (2 * radius + 1);
            for (int y = 0; y < h; y++) {
                for (int c = 0; c < 3; c++) {
                    double sum = 0;
                    int row = y * w * 3;
                    for (int x = -radius; x <= radius; x++) {
                        sum += buf[row + Math.min(w - 1, Math.max(0, x)) * 3 + c];
                    }
                    for (int x = 0; x < w; x++) {
                        tmp[row + x * 3 + c] = sum * norm;
                        int add = Math.min(w - 1, x + radius + 1);
                        int sub = Math.max(0, x - radius);
                        sum += buf[row + add * 3 + c] - buf[row + sub * 3 + c];
                    }
                }
            }
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    double sum = 0;
                    for (int y = -radius; y <= radius; y++) {
                        sum += tmp[Math.min(h - 1, Math.max(0, y)) * w * 3 + x * 3 + c];
                    }
                    for (int y = 0; y < h; y++) {
                        buf[y * w * 3 + x * 3 + c] = sum * norm;
                        int add = Math.min(h - 1, y + radius + 1);
                        int sub = Math.max(0, y - radius);
                        sum += tmp[add * w * 3 + x * 3 + c] - tmp[sub * w * 3 + x * 3 + c];
                    }
                }
            }
        }

        private static int[] tonemap(double[] acc, int w, int h, double exposure) {
            int[] px = new int[w * h];
            for (int i = 0; i < px.length; i++) {
                int o = i * 3;
                double r = acc[o] * exposure;
                double g = acc[o + 1] * exposure;
                double b = acc[o + 2] * exposure;
                // vibrance: push chroma a bit, galaxy photos are colourful in reality
                double l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                r = l + (r - l) * 1.5;
                g = l + (g - l) * 1.5;
                b = l + (b - l) * 1.5;
                px[i] = (int) (255 * tm(r)) << 16 | (int) (255 * tm(g)) << 8 | (int) (255 * tm(b));
            }
            return px;
        }

        private static double tm(double v) {
            v = Math.max(0, v);
            return Math.pow(Math.min(1, v / (1 + v)), 1 / 2.2);
        }
    }

    private static double gauss(Random rnd) {
        return rnd.nextGaussian();
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.min(1, Math.max(0, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double[] mixRgb(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t};
    }
}
