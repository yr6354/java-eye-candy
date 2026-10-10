package candy;

import java.io.File;
import java.util.stream.IntStream;

/**
 * A from-scratch Whitted-style ray tracer: reflections, refraction with Fresnel,
 * glossy checkerboard, and area-light soft shadows. No engine, no library.
 *
 * 纯手写光线追踪：镜面反射、玻璃折射（Schlick 菲涅尔）、棋盘地面、面光源软阴影。
 */
public final class RayTracer {

    private static final double INF = 1e18;
    private static final int MAX_DEPTH = 6;
    private static final V SUN_DIR = new V(-0.33, 0.15, -0.93).norm();
    private static final double SUN_RADIUS = 0.055;
    private static final V SUN_COLOR = new V(1.00, 0.95, 0.86);
    private static final double SUN_POWER = 3.40;
    private static int SHADOW_SAMPLES = 6;

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args).help("""
                Glass & Chrome - a tiny ray tracer (pure JDK)

                  java src/candy/RayTracer.java [options]

                  -w, -h <px>     output size (default 1440x810)
                  --aa <n>        samples per pixel, n x n grid (default 3)
                  --shadow <n>    shadow rays per hit (default 12)
                  --exposure <x>  exposure multiplier (default 1.0)
                  --out <file>    output png (default docs/raytracer.png)
                """);
        int w = cli.i("w", 1440);
        int h = cli.i("h", 810);
        int aa = Math.max(1, cli.i("aa", 3));
        SHADOW_SAMPLES = Math.max(1, cli.i("shadow", 12));
        double exposure = cli.d("exposure", 1.0);
        File out = new File(cli.s("out", "docs/raytracer.png"));

        Scene scene = Scene.demo();
        if (cli.flag("hits")) { // dev helper: which object owns each pixel
            printHitMap(scene, 110);
            return;
        }
        int[] px = new int[w * h];
        long t0 = System.nanoTime();
        IntStream.range(0, h).parallel().forEach(y -> {
            int[] line = new int[w];
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0;
                for (int sy = 0; sy < aa; sy++) {
                    for (int sx = 0; sx < aa; sx++) {
                        double u = (2 * (x + (sx + Math.random()) / aa) / w - 1) * scene.aspect;
                        double v = 1 - 2 * (y + (sy + Math.random()) / aa) / h;
                        V dir = scene.camU.mul(u * scene.fov).add(scene.camV.mul(v * scene.fov))
                                .sub(scene.camW).norm();
                        V c = radiance(scene, scene.eye, dir, 0, false);
                        r += c.x;
                        g += c.y;
                        b += c.z;
                    }
                }
                double n = aa * aa;
                line[x] = pack(r / n, g / n, b / n, exposure);
            }
            System.arraycopy(line, 0, px, y * w, w);
        });
        Gif.writePng(out, px, w, h);
        System.out.printf("raytracer  %dx%d  aa=%dx%d  shadow=%d rays%n", w, h, aa, aa, SHADOW_SAMPLES);
        System.out.printf("wrote      %s  (%.2f MB, %.1fs)%n",
                out, out.length() / 1048576.0, (System.nanoTime() - t0) / 1e9);
    }

    private static void printHitMap(Scene s, int cols) {
        int rows = (int) Math.round(cols / s.aspect * 0.5);
        for (int y = 0; y < rows; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < cols; x++) {
                double u = (2 * (x + 0.5) / cols - 1) * s.aspect;
                double v = 1 - 2 * (y + 0.5) / rows;
                V dir = s.camU.mul(u * s.fov).add(s.camV.mul(v * s.fov)).sub(s.camW).norm();
                Hit h = s.intersect(s.eye, dir);
                if (h == null) {
                    sb.append(' ');
                } else if (h.mat == s.ground) {
                    sb.append(h.albedo.x > 0.4 ? ',' : ':');
                } else {
                    int idx = 0;
                    for (int i = 0; i < s.spheres.length; i++) {
                        if (s.spheres[i].mat == h.mat) {
                            idx = i;
                        }
                    }
                    sb.append((char) ('0' + idx));
                }
            }
            System.out.println(sb);
        }
    }

    // ---------------------------------------------------------------- tracing

    private static V radiance(Scene s, V o, V d, int depth, boolean insideGlass) {
        Hit hit = s.intersect(o, d);
        if (hit == null) {
            return insideGlass ? new V(0, 0, 0) : sky(d);
        }
        Mat m = hit.mat;
        V albedo = hit.albedo != null ? hit.albedo : m.albedo;
        V p = o.add(d.mul(hit.t));
        V n = hit.n;
        boolean entering = m.glass && d.dot(n) < 0;
        if (m.glass && !entering) {
            n = n.mul(-1); // we are inside the glass, use the inner normal
        }

        V color = m.emissive;

        // ---- sun: diffuse + Blinn-Phong specular, softened by area sampling
        double shadow = softShadow(s, p, n);
        double ndl = Math.max(0, n.dot(SUN_DIR));
        if (ndl > 0) {
            V sun = SUN_COLOR.mul(SUN_POWER * ndl * shadow);
            color = color.add(albedo.mul(sun));
            V half = SUN_DIR.sub(d).norm();
            double spec = Math.pow(Math.max(0, n.dot(half)), m.shininess);
            color = color.add(sun.mul(spec * m.specular));
        }

        // ---- cheap sky ambient so shadows are not pitch black
        color = color.add(albedo.mul(hemisphere(n)).mul(0.30));
        // ---- soft fill from the viewer side, keeps front faces readable
        color = color.add(albedo.mul(new V(0.42, 0.46, 0.52)).mul(Math.max(0, n.dot(d.mul(-1))) * 0.22));

        if (depth >= MAX_DEPTH) {
            return color;
        }

        double cosTheta = Math.max(0, -d.dot(n));

        // ---- dielectric: Fresnel split between reflection and refraction
        if (m.glass) {
            double eta = entering ? 1.0 / m.ior : m.ior;
            double cosi = Math.max(0, -d.dot(n));
            double cos2t = 1 - eta * eta * (1 - cosi * cosi);
            double r0 = (m.ior - 1) / (m.ior + 1);
            r0 *= r0;
            if (cos2t < 0) { // total internal reflection
                V rd = d.sub(n.mul(2 * d.dot(n)));
                return color.add(radiance(s, p.add(n.mul(1e-4)), rd, depth + 1, insideGlass).mul(m.tint));
            }
            double sinT2 = eta * eta * (1 - cosi * cosi);
            double cosT = Math.sqrt(Math.max(0, 1 - sinT2));
            double fresnel = r0 + (1 - r0) * Math.pow(1 - (entering ? cosi : cosT), 5);
            V rd = d.sub(n.mul(2 * d.dot(n)));
            V refl = radiance(s, p.add(n.mul(1e-4)), rd, depth + 1, insideGlass);
            V refr = d.mul(eta).add(n.mul(eta * cosi - cosT)).norm();
            V refrCol = radiance(s, p.sub(n.mul(1e-4)), refr, depth + 1, entering).mul(m.tint);
            return color.add(refl.mul(fresnel)).add(refrCol.mul(1 - fresnel));
        }

        // ---- opaque: reflection with a Schlick Fresnel term
        if (m.reflect > 0) {
            V rd = d.sub(n.mul(2 * d.dot(n)));
            double f = 0.04 + 0.96 * Math.pow(1 - cosTheta, 5);
            double amount = Math.min(1.0, m.reflect * (1 + m.fresnel * f));
            V reflected = radiance(s, p.add(n.mul(1e-4)), rd, depth + 1, insideGlass).mul(m.tint);
            color = color.add(reflected.mul(amount));
        }
        return color;
    }

    private static double softShadow(Scene s, V p, V n) {
        V up = Math.abs(SUN_DIR.y) < 0.9 ? new V(0, 1, 0) : new V(1, 0, 0);
        V t = SUN_DIR.cross(up).norm();
        V b = SUN_DIR.cross(t);
        int clear = 0;
        for (int i = 0; i < SHADOW_SAMPLES; i++) {
            double r = SUN_RADIUS * Math.sqrt(Math.random());
            double a = Math.random() * Math.PI * 2;
            V dir = SUN_DIR.add(t.mul(r * Math.cos(a))).add(b.mul(r * Math.sin(a))).norm();
            if (n.dot(dir) <= 0) {
                continue;
            }
            if (s.intersect(p.add(n.mul(2e-3)).add(dir.mul(2e-3)), dir) == null) {
                clear++;
            }
        }
        return clear / (double) SHADOW_SAMPLES;
    }

    /** Sky gradient plus a sun glow, so reflections have somewhere to look at. */
    static V sky(V d) {
        double t = Math.max(0, d.y);
        V horizon = new V(0.74, 0.35, 0.18);
        V mid = new V(0.09, 0.17, 0.44);
        V zenith = new V(0.010, 0.022, 0.085);
        V c = t < 0.07
                ? mix(horizon, mid, t / 0.07)
                : mix(mid, zenith, (t - 0.07) / 0.93);
        if (d.y < 0) {
            c = mix(c, new V(0.05, 0.05, 0.07), Math.min(1, -d.y * 3));
        }
        double sun = Math.max(0, d.dot(SUN_DIR));
        c = c.add(SUN_COLOR.mul(Math.pow(sun, 4000) * 22 + Math.pow(sun, 90) * 1.6));
        return c;
    }

    private static V hemisphere(V n) {
        return new V(0.35, 0.42, 0.55).mul(0.5 + 0.5 * n.y);
    }

    // ---------------------------------------------------------------- scene

    static final class Scene {
        final V eye;
        final V camU;
        final V camV;
        final V camW;
        final double aspect;
        final Sphere[] spheres;
        final Mat ground;

        Scene(V eye, V look, double fovDeg, int w, int h, Mat ground, Sphere... spheres) {
            this.eye = eye;
            this.ground = ground;
            this.spheres = spheres;
            this.aspect = w / (double) h;
            V wv = eye.sub(look).norm();
            V up = new V(0, 1, 0);
            this.camU = up.cross(wv).norm();
            this.camV = wv.cross(camU);
            this.camW = wv;
            this.fov = Math.tan(Math.toRadians(fovDeg) / 2);
        }

        final double fov;

        Hit intersect(V o, V d) {
            Hit best = null;
            if (Math.abs(d.y) > 1e-9) {
                double t = -o.y / d.y;
                if (t > 1e-4 && (best == null || t < best.t)) {
                    double cx = o.x + d.x * t;
                    double cz = o.z + d.z * t;
                    boolean light = ((long) Math.floor(cx * 0.5) + (long) Math.floor(cz * 0.5)) % 2 == 0;
                    Hit hit = new Hit(t, new V(0, 1, 0), ground);
                    hit.albedo = light ? new V(0.62, 0.62, 0.64) : new V(0.030, 0.030, 0.036);
                    best = hit;
                }
            }
            for (Sphere sp : spheres) {
                double t = sp.hit(o, d);
                if (t > 1e-4 && (best == null || t < best.t)) {
                    V p = o.add(d.mul(t));
                    Hit hit = new Hit(t, p.sub(sp.center).mul(1 / sp.radius), sp.mat);
                    best = hit;
                }
            }
            return best;
        }

        static Scene demo() {
            Mat chrome = new Mat(new V(0.55, 0.57, 0.60), 1.0, 0, 1200)
                    .reflect(0.92).tint(new V(1.0, 1.0, 1.0));
            Mat gold = new Mat(new V(0.72, 0.51, 0.18), 0.55, 320, 90).reflect(0.85)
                    .tint(new V(0.97, 0.76, 0.34));
            Mat glass = new Mat(new V(0.04, 0.04, 0.05), 0, 0, 900).glass(1.52)
                    .tint(new V(0.86, 0.96, 0.92));
            Mat ceramic = new Mat(new V(0.62, 0.22, 0.10), 0.02, 0.35, 160).reflect(0.05);
            Mat teal = new Mat(new V(0.09, 0.34, 0.36), 0.02, 0.55, 260).reflect(0.08);
            Mat red = new Mat(new V(0.62, 0.16, 0.12), 0.02, 0.45, 120).reflect(0.05);
            Mat ground = new Mat(new V(0.80, 0.80, 0.82), 0, 0, 1).reflect(0.14).fresnel(2.2);

            return new Scene(
                    new V(0, 1.34, 5.15), new V(0, 0.98, -0.35), 39, 16, 9, ground,
                    new Sphere(new V(-1.34, 0.84, 0.00), 0.84, chrome),
                    new Sphere(new V(1.32, 0.76, 0.10), 0.76, glass),
                    new Sphere(new V(0.04, 0.40, -1.95), 0.40, ceramic),
                    new Sphere(new V(1.94, 0.29, 1.55), 0.29, gold),
                    new Sphere(new V(-1.96, 0.27, 1.28), 0.27, teal),
                    new Sphere(new V(0.26, 0.21, 1.90), 0.21, red));
        }
    }

    static final class Sphere {
        final V center;
        final double radius;
        final Mat mat;

        Sphere(V c, double r, Mat m) {
            this.center = c;
            this.radius = r;
            this.mat = m;
        }

        double hit(V o, V d) {
            V oc = o.sub(center);
            double b = oc.dot(d);
            double c = oc.dot(oc) - radius * radius;
            double disc = b * b - c;
            if (disc < 0) {
                return -1;
            }
            double sq = Math.sqrt(disc);
            double t = -b - sq;
            if (t > 1e-4) {
                return t;
            }
            t = -b + sq;
            return t > 1e-4 ? t : -1;
        }
    }

    static final class Hit {
        final double t;
        final V n;
        final Mat mat;
        V albedo;

        Hit(double t, V n, Mat mat) {
            this.t = t;
            this.n = n;
            this.mat = mat;
        }
    }

    static final class Mat {
        final V albedo;
        double reflect;
        double specular;
        double shininess;
        double fresnel;
        double ior;
        boolean glass;
        V tint = new V(1, 1, 1);
        V emissive = new V(0, 0, 0);

        Mat(V albedo, double reflect, double specular, double shininess) {
            this.albedo = albedo;
            this.reflect = reflect;
            this.specular = specular;
            this.shininess = shininess;
        }

        Mat reflect(double r) {
            reflect = r;
            return this;
        }

        Mat glass(double ior) {
            this.glass = true;
            this.ior = ior;
            return this;
        }

        Mat tint(V t) {
            tint = t;
            return this;
        }

        Mat fresnel(double f) {
            fresnel = f;
            return this;
        }
    }

    // ---------------------------------------------------------------- math

    static final class V {
        final double x;
        final double y;
        final double z;

        V(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        V add(V o) {
            return new V(x + o.x, y + o.y, z + o.z);
        }

        V sub(V o) {
            return new V(x - o.x, y - o.y, z - o.z);
        }

        V mul(double s) {
            return new V(x * s, y * s, z * s);
        }

        V mul(V o) {
            return new V(x * o.x, y * o.y, z * o.z);
        }

        double dot(V o) {
            return x * o.x + y * o.y + z * o.z;
        }

        V cross(V o) {
            return new V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
        }

        V norm() {
            double l = Math.sqrt(x * x + y * y + z * z);
            return l == 0 ? this : new V(x / l, y / l, z / l);
        }
    }

    static V mix(V a, V b, double t) {
        return new V(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    static int pack(double r, double g, double b, double exposure) {
        return (int) (255 * tm(r * exposure)) << 16
                | (int) (255 * tm(g * exposure)) << 8
                | (int) (255 * tm(b * exposure));
    }

    /** Filmic-ish tonemap + gamma, keeps highlights from clipping to flat white. */
    private static double tm(double v) {
        v = Math.max(0, v);
        return Math.pow(Math.min(1, v / (1 + v)), 1 / 2.2);
    }
}
