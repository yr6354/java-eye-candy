package candy;

import java.io.File;
import java.util.stream.IntStream;

/**
 * Fractal forge: Mandelbrot / Julia / Burning Ship, smooth-colored and
 * multi-threaded, straight to a PNG. Pure JDK.
 *
 * 分形渲染器：平滑着色的逃逸时间算法 + 余弦调色板 + 多线程渲染 + 超采样抗锯齿。
 */
public final class Fractal {

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args).help("""
                FractalForge - smooth escape-time fractal renderer (pure JDK)

                  java src/candy/Fractal.java [options]

                  --type <mandelbrot|julia|ship>   fractal family (default mandelbrot)
                  -w, -h <px>                      output size (default 1920x1080)
                  --iter <n>                       max iterations (default 900)
                  --zoom <x>                       magnification (default 1)
                  --cx, --cy <value>               center of the view (default -0.6, 0)
                  --julia-cx, --julia-cy <value>   Julia seed (default -0.8, 0.156)
                  --ss <n>                         supersampling, 1..4 (default 2)
                  --palette <rainbow|ice|ember|neon|mono>  (default ice)
                  --shift <value>                  palette offset (default 0)
                  --scale <value>                  color ramp length (default 48)
                  --out <file.png>                 output path (default docs/fractal.png)
                """);

        String type = cli.s("type", "mandelbrot").toLowerCase();
        int w = cli.i("w", 1920);
        int h = cli.i("h", 1080);
        int maxIter = cli.i("iter", 900);
        double zoom = cli.d("zoom", 1);
        double cx = cli.d("cx", type.equals("julia") ? 0 : -0.6);
        double cy = cli.d("cy", 0);
        double jcx = cli.d("julia-cx", -0.8);
        double jcy = cli.d("julia-cy", 0.156);
        int ss = Math.max(1, Math.min(4, cli.i("ss", 2)));
        String palette = cli.s("palette", "ice").toLowerCase();
        double shift = cli.d("shift", 0);
        double scale = cli.d("scale", 48);
        File out = new File(cli.s("out", "docs/fractal.png"));

        // viewport: 3.2 units wide at zoom 1, keeps the classic framing
        double spanX = 3.2 / zoom;
        double spanY = spanX * h / (double) w;
        double x0 = cx - spanX / 2;
        double y0 = cy - spanY / 2;

        int[] px = new int[w * h];
        long t0 = System.nanoTime();
        IntStream.range(0, h).parallel().forEach(y -> {
            int[] line = new int[w];
            for (int x = 0; x < w; x++) {
                double sr = 0, sg = 0, sb = 0;
                for (int sy = 0; sy < ss; sy++) {
                    for (int sx = 0; sx < ss; sx++) {
                        double re = x0 + (x + (sx + 0.5) / ss) * spanX / w;
                        double im = y0 + (y + (sy + 0.5) / ss) * spanY / h;
                        double[] c = escape(type, re, im, jcx, jcy, maxIter);
                        double mu = c[0] + 1 - Math.log(Math.log(Math.max(c[1], 1.0001))) / Math.log(2);
                        double t = mu / scale + shift;
                        double[] rgb = c[2] < 0 ? new double[]{0, 0, 0} : Palette.cosine(palette, t);
                        sr += rgb[0];
                        sg += rgb[1];
                        sb += rgb[2];
                    }
                }
                double n = ss * ss;
                line[x] = pack(sr / n, sg / n, sb / n);
            }
            System.arraycopy(line, 0, px, y * w, w);
        });

        Gif.writePng(out, px, w, h);
        System.out.printf("fractal  type=%s  %dx%d  iter=%d  ss=%dx  zoom=%.4g%n",
                type, w, h, maxIter, ss, zoom);
        System.out.printf("wrote    %s  (%.2f MB, %.1fs)%n",
                out, out.length() / 1048576.0, (System.nanoTime() - t0) / 1e9);
    }

    /**
     * Escape-time iteration. Returns {smoothIter, |z|^2 at escape, status}
     * where status is -1 for points inside the set.
     */
    private static double[] escape(String type, double cre, double cim, double jcx, double jcy, int maxIter) {
        double zr, zi, cr, ci;
        switch (type) {
            case "julia" -> {
                zr = cre;
                zi = cim;
                cr = jcx;
                ci = jcy;
            }
            default -> {
                zr = 0;
                zi = 0;
                cr = cre;
                ci = cim;
            }
        }
        double zr2 = zr * zr;
        double zi2 = zi * zi;
        int n = 0;
        while (n < maxIter && zr2 + zi2 <= 256) {
            double nextR = zr2 - zi2 + cr;
            double nextI = 2 * zr * zi + ci;
            if (type.equals("ship")) {
                nextR = Math.abs(nextR);
                nextI = -Math.abs(nextI);
            }
            zr = nextR;
            zi = nextI;
            zr2 = zr * zr;
            zi2 = zi * zi;
            n++;
        }
        double mod2 = zr2 + zi2;
        return new double[]{n, mod2, n >= maxIter ? -1 : 0};
    }

    private static int pack(double r, double g, double b) {
        int ri = (int) (Math.min(1, Math.max(0, r)) * 255);
        int gi = (int) (Math.min(1, Math.max(0, g)) * 255);
        int bi = (int) (Math.min(1, Math.max(0, b)) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Inigo Quilez style cosine gradient palettes. */
    static final class Palette {
        private Palette() {
        }

        static double[] cosine(String name, double t) {
            double[] a;
            double[] b;
            double[] c;
            double[] d;
            switch (name) {
                case "rainbow" -> {
                    a = new double[]{0.5, 0.5, 0.5};
                    b = new double[]{0.5, 0.5, 0.5};
                    c = new double[]{1, 1, 1};
                    d = new double[]{0.00, 0.33, 0.67};
                }
                case "ember" -> {
                    a = new double[]{0.65, 0.42, 0.28};
                    b = new double[]{0.35, 0.42, 0.32};
                    c = new double[]{1.0, 1.0, 1.0};
                    d = new double[]{0.00, 0.12, 0.28};
                }
                case "neon" -> {
                    a = new double[]{0.55, 0.35, 0.55};
                    b = new double[]{0.45, 0.55, 0.45};
                    c = new double[]{1.0, 1.0, 1.0};
                    d = new double[]{0.10, 0.40, 0.70};
                }
                case "mono" -> {
                    a = new double[]{0.5, 0.5, 0.5};
                    b = new double[]{0.5, 0.5, 0.5};
                    c = new double[]{1, 1, 1};
                    d = new double[]{0.15, 0.15, 0.20};
                }
                default -> { // ice
                    a = new double[]{0.45, 0.52, 0.62};
                    b = new double[]{0.45, 0.42, 0.38};
                    c = new double[]{1, 1, 1};
                    d = new double[]{0.55, 0.60, 0.72};
                }
            }
            return new double[]{ch(a[0], b[0], c[0], d[0], t),
                    ch(a[1], b[1], c[1], d[1], t),
                    ch(a[2], b[2], c[2], d[2], t)};
        }

        private static double ch(double a, double b, double c, double d, double t) {
            return a + b * Math.cos(2 * Math.PI * (c * t + d));
        }
    }
}
