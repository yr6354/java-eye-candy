package candy;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Image -> terminal art. By default it prints ANSI truecolor art, so any photo
 * turns into a poster you can scroll. With --mono it degrades to plain text.
 *
 * 图片转字符画：默认输出 24 位真彩色 ANSI 艺术字，--mono 可以退化成纯文本。
 */
public final class AsciiView {

    private static final String RAMP = " .'`^\",:;Il!i~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args).help("""
                AsciiView - render an image as terminal art (pure JDK)

                  java src/candy/AsciiView.java <image> [options]

                  --cols <n>     character columns (default 110)
                  --mono         plain text instead of ANSI color
                  --hue          debug map: hue letter + brightness, no ANSI
                  --invert       flip the ramp, for light-background terminals
                  --stats        print pixel statistics instead of the picture
                  --ramp <s>     custom brightness ramp, dark -> bright
                """);
        String path = cli.s("in", null);
        if (path == null) {
            for (String a : cli.raw()) {
                if (!a.startsWith("-")) {
                    path = a;
                    break;
                }
            }
        }
        if (path == null) {
            System.out.println("usage: AsciiView <image> [--cols 110] [--mono] [--stats]");
            return;
        }
        BufferedImage src = ImageIO.read(new File(path));
        if (src == null) {
            System.out.println("cannot read image: " + path);
            return;
        }
        int cols = cli.i("cols", 110);
        String ramp = cli.s("ramp", RAMP);
        boolean mono = cli.flag("mono");
        boolean hue = cli.flag("hue");
        boolean invert = cli.flag("invert");

        if (hue) {
            System.out.print(hueMap(src, cols));
            return;
        }
        if (cli.flag("stats")) {
            Stats.print(src);
            return;
        }

        int rows = Math.max(1, (int) Math.round(cols * (double) src.getHeight() / src.getWidth() * 0.5));
        int[] px = new int[cols * rows];
        for (int y = 0; y < rows; y++) {
            int sy0 = y * src.getHeight() / rows;
            int sy1 = Math.max(sy0 + 1, (y + 1) * src.getHeight() / rows);
            for (int x = 0; x < cols; x++) {
                int sx0 = x * src.getWidth() / cols;
                int sx1 = Math.max(sx0 + 1, (x + 1) * src.getWidth() / cols);
                long r = 0, g = 0, b = 0, n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = src.getRGB(sx, sy);
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                px[y * cols + x] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
            }
        }

        StringBuilder sb = new StringBuilder(px.length * 20);
        int lastColor = -1;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            double lum = lum(c);
            double v = invert ? 1 - lum : lum;
            char ch = ramp.charAt(Math.min(ramp.length() - 1, (int) (v * ramp.length())));
            if (!mono && c != lastColor) {
                sb.append("\u001b[38;2;").append((c >> 16) & 0xFF).append(';')
                        .append((c >> 8) & 0xFF).append(';').append(c & 0xFF).append('m');
                lastColor = c;
            }
            sb.append(ch);
            if ((i + 1) % cols == 0) {
                sb.append(mono ? '\n' : "\u001b[0m\n");
                lastColor = -1;
            }
        }
        if (!mono) {
            sb.append("\u001b[0m");
        }
        System.out.print(sb);
    }

    /**
     * Hue-coded map: '.' = dark, lowercase = mid, uppercase = bright,
     * letters are R O Y G C B V M W. Handy when you cannot see the picture.
     */
    static String hueMap(BufferedImage img, int cols) {
        int w = img.getWidth(), h = img.getHeight();
        int rows = Math.max(1, (int) Math.round(cols * h / (double) w * 0.5));
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                long r = 0, g = 0, b = 0, n = 0;
                for (int sy = y * h / rows; sy < Math.max(y * h / rows + 1, (y + 1) * h / rows); sy++) {
                    for (int sx = x * w / cols; sx < Math.max(x * w / cols + 1, (x + 1) * w / cols); sx++) {
                        int c = img.getRGB(Math.min(sx, w - 1), Math.min(sy, h - 1));
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                sb.append(hueChar((int) (r / n), (int) (g / n), (int) (b / n)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static char hueChar(int r, int g, int b) {
        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        double v = 0.2126 * r / 255 + 0.7152 * g / 255 + 0.0722 * b / 255;
        if (v < 0.13) {
            return '.';
        }
        char letter;
        if (hsb[1] < 0.09f) {
            letter = 'W';
        } else {
            float deg = hsb[0] * 360;
            if (deg < 18 || deg >= 340) {
                letter = 'R';
            } else if (deg < 45) {
                letter = 'O';
            } else if (deg < 70) {
                letter = 'Y';
            } else if (deg < 165) {
                letter = 'G';
            } else if (deg < 200) {
                letter = 'C';
            } else if (deg < 255) {
                letter = 'B';
            } else if (deg < 292) {
                letter = 'V';
            } else {
                letter = 'M';
            }
        }
        return v < 0.5 ? Character.toLowerCase(letter) : letter;
    }

    static double lum(int c) {
        return (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
    }

    /** Numeric sanity check, useful when you cannot look at the picture itself. */
    static final class Stats {
        static void print(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            long sr = 0, sg = 0, sb = 0;
            int[] hsv = new int[12];
            int distinct = 0;
            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.getRGB(x, y) & 0xFFFFFF;
                    sr += (c >> 16) & 0xFF;
                    sg += (c >> 8) & 0xFF;
                    sb += c & 0xFF;
                    float[] hsb = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
                    if (hsb[2] > 0.12f && hsb[1] > 0.15f) {
                        hsv[Math.min(11, (int) (hsb[0] * 12))]++;
                    }
                    if (seen.size() < 400000) {
                        seen.add(c);
                    }
                }
            }
            distinct = seen.size();
            long n = (long) w * h;
            System.out.printf("size       %dx%d%n", w, h);
            System.out.printf("mean rgb   %.1f %.1f %.1f%n", sr / (double) n, sg / (double) n, sb / (double) n);
            System.out.printf("distinct   %d colors%s%n", distinct, distinct >= 400000 ? "+" : "");
            StringBuilder hue = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hue.append(String.format("%2d:%5d%%  ", i * 30, hsv[i] * 100 / n));
                if (i % 4 == 3) {
                    hue.append('\n');
                }
            }
            System.out.print("hue spread " + hue);

            int cols = 72;
            int rows = Math.max(8, (int) Math.round(cols * h / (double) w * 0.5));
            StringBuilder art = new StringBuilder();
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    double v = 0;
                    int cnt = 0;
                    for (int sy = y * h / rows; sy < Math.max(y * h / rows + 1, (y + 1) * h / rows); sy++) {
                        for (int sx = x * w / cols; sx < Math.max(x * w / cols + 1, (x + 1) * w / cols); sx++) {
                            v += lum(img.getRGB(Math.min(sx, w - 1), Math.min(sy, h - 1)));
                            cnt++;
                        }
                    }
                    art.append(RAMP.charAt(Math.min(RAMP.length() - 1, (int) (v / cnt * RAMP.length()))));
                }
                art.append('\n');
            }
            System.out.print(art);
        }
    }
}
