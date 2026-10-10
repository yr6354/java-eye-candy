package candy;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Digital rain, in two flavours: live ANSI in your terminal, or an exported
 * animated GIF rendered through java.awt. Same particle state, two front ends.
 *
 * 数字雨：既能直接在终端里用 ANSI 真彩色播放，也能导出成 GIF 动图。
 */
public final class MatrixRain {

    /** halfwidth katakana + digits + a few latin letters, the classic alphabet */
    private static final String ALPHABET = buildAlphabet();

    private static String buildAlphabet() {
        StringBuilder sb = new StringBuilder();
        for (char c = '\uFF66'; c <= '\uFF9D'; c++) {
            sb.append(c);
        }
        sb.append("0123456789");
        sb.append("ZENITHWXKVB");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args).help("""
                MatrixRain - digital rain in your terminal (pure JDK)

                  java src/candy/MatrixRain.java [options]

                  --cols <n>      columns, 0 = from terminal (default 0)
                  --rows <n>      rows, 0 = from terminal (default 0)
                  --fps <n>       target frame rate (default 24)
                  --density <x>   how eager columns are to start a drop (default 1.0)
                  --frames <n>    stop after n frames, 0 = forever (default 0)
                  --glitch <x>    chance of a cyan/white glitch column (default 0.06)
                  --record <gif>  render to an animated gif instead of the terminal
                  --record-frames <n>  frames to export (default 96)
                  --record-still <png> also save one full-colour frame as png
                  --cell <px>     glyph cell height for gif export (default 18)
                """);

        double densityScale = cli.d("density", 1.0);
        int fps = Math.max(1, cli.i("fps", 24));
        int maxFrames = cli.i("frames", 0);
        double glitch = cli.d("glitch", 0.06);
        String record = cli.s("record", null);

        if (record != null) {
            record(new File(record), cli, fps, glitch, densityScale);
            return;
        }
        playTerminal(cli, fps, maxFrames, glitch, densityScale);
    }

    // ------------------------------------------------------------- terminal

    private static void playTerminal(Cli cli, int fps, int maxFrames, double glitch, double density)
            throws Exception {
        int cols = cli.i("cols", 0);
        int rows = cli.i("rows", 0);
        if (cols <= 0 || rows <= 0) {
            int[] size = terminalSize();
            if (cols <= 0) {
                cols = size[0];
            }
            if (rows <= 0) {
                rows = size[1];
            }
        }
        Rain rain = new Rain(cols, rows, glitch, density, new Random());
        StringBuilder out = new StringBuilder(cols * rows * 22);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.print("\u001b[0m\u001b[?25h\n");
            System.out.flush();
        }));
        System.out.print("\u001b[?25l\u001b[2J");
        long frameNanos = 1_000_000_000L / fps;
        int frame = 0;
        while (maxFrames == 0 || frame < maxFrames) {
            rain.step();
            out.setLength(0);
            out.append("\u001b[H");
            for (int y = 0; y < rows; y++) {
                int last = -1;
                for (int x = 0; x < cols; x++) {
                    double g = rain.glow[x][y];
                    if (g < 0.13) {
                        if (last != -1) {
                            out.append("\u001b[0m");
                            last = -1;
                        }
                        out.append(' ');
                        continue;
                    }
                    int[] rgb = rain.colorOf(x, g);
                    int packed = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                    if (packed != last) {
                        out.append("\u001b[38;2;").append(rgb[0]).append(';')
                                .append(rgb[1]).append(';').append(rgb[2]).append('m');
                        last = packed;
                    }
                    out.append(rain.glyph[x][y]);
                }
                if (last != -1) {
                    out.append("\u001b[0m");
                }
                out.append('\n');
            }
            System.out.print(out);
            System.out.flush();
            frame++;
            Thread.sleep(Math.max(0, frameNanos / 1_000_000 - 3));
        }
        System.out.print("\u001b[0m\u001b[?25h\n");
    }

    /** Best effort terminal size: COLUMNS/LINES, then stty, then 100x28. */
    private static int[] terminalSize() {
        String c = System.getenv("COLUMNS");
        String l = System.getenv("LINES");
        if (c != null && l != null) {
            try {
                return new int[]{Integer.parseInt(c.trim()), Integer.parseInt(l.trim())};
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] parts = s.split("\\s+");
            if (parts.length == 2) {
                return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[0])};
            }
        } catch (Exception ignored) {
            // not a unix tty
        }
        return new int[]{100, 28};
    }

    // ------------------------------------------------------------- gif export

    private static void record(File out, Cli cli, int fps, double glitch, double density) throws Exception {
        int frames = cli.i("record-frames", 96);
        int cell = Math.max(10, cli.i("cell", 18));
        int cols = cli.i("cols", 66);
        int rows = cli.i("rows", 0);
        int cellW = (int) Math.round(cell * 0.66);
        if (rows <= 0) {
            rows = Math.max(8, (int) Math.round(cols * cellW * 9.0 / 16 / cell));
        }
        int w = cols * cellW;
        int h = rows * cell;
        Rain rain = new Rain(cols, rows, glitch, density, new Random(7));
        for (int i = 0; i < 60; i++) { // warm up so frame 1 is not an empty screen
            rain.step();
        }
        Font font = new Font("Monospaced", Font.BOLD, (int) Math.round(cell * 1.05));
        List<BufferedImage> gifFrames = new ArrayList<>();
        File still = cli.s("record-still", null) == null ? null : new File(cli.s("record-still", ""));
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            rain.step();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            paint(g, rain, w, h, cols, rows, cell, cellW, font);
            g.dispose();
            if (still != null && f == frames / 2) {
                javax.imageio.ImageIO.write(img, "png", still);
                System.out.printf("wrote    %s%n", still);
            }
            // quantise first, so the gif encoder gets indexed frames directly
            BufferedImage idx = Gif.frame(w, h);
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            Gif.blit(idx, px, w, h);
            gifFrames.add(idx);
        }
        Gif.writeAnimated(out, gifFrames, Math.max(20, 1000 / fps));
        System.out.printf("rain     %dx%d cells  %dx%d px  %d frames%n", cols, rows, w, h, frames);
        System.out.printf("wrote    %s  (%.2f MB, %.1fs)%n",
                out, out.length() / 1048576.0, (System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------------- the rain

    private static void paint(Graphics2D g, Rain rain, int w, int h, int cols, int rows,
                              int cell, int cellW, Font font) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        int baseline = (int) (cell * 0.82);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double glow = rain.glow[x][y];
                if (glow < 0.13) {
                    continue;
                }
                int[] rgb = rain.colorOf(x, glow);
                g.setColor(new Color(rgb[0], rgb[1], rgb[2]));
                g.drawString(String.valueOf(rain.glyph[x][y]), x * cellW, y * cell + baseline);
            }
        }
    }

    /** Shared state machine: drops, glow decay, glyph mutation. */
    static final class Rain {
        final int cols;
        final int rows;
        final double[][] glow;
        final char[][] glyph;
        final double[] head;
        final double[] speed;
        final double[] tail;
        final int[] cooldown;
        final boolean[] glitchCol;
        final double glitchChance;
        final double density;
        final Random rnd;

        Rain(int cols, int rows, double glitchChance, double density, Random rnd) {
            this.cols = cols;
            this.rows = rows;
            this.glitchChance = glitchChance;
            this.density = density;
            this.rnd = rnd;
            glow = new double[cols][rows];
            glyph = new char[cols][rows];
            head = new double[cols];
            speed = new double[cols];
            tail = new double[cols];
            cooldown = new int[cols];
            glitchCol = new boolean[cols];
            for (int x = 0; x < cols; x++) {
                for (int y = 0; y < rows; y++) {
                    glyph[x][y] = randomGlyph();
                }
                cooldown[x] = rnd.nextInt(Math.max(1, rows / 2));
            }
        }

        void step() {
            for (int x = 0; x < cols; x++) {
                for (int y = 0; y < rows; y++) {
                    glow[x][y] *= 0.90;
                    if (glow[x][y] < 0.02) {
                        glow[x][y] = 0;
                    }
                }
            }
            for (int x = 0; x < cols; x++) {
                if (tail[x] > 0) {
                    head[x] += speed[x];
                    int y = (int) head[x];
                    if (y >= 0 && y < rows) {
                        glow[x][y] = 1.0;
                        glyph[x][y] = randomGlyph();
                    }
                    tail[x] -= 1;
                    if (tail[x] <= 0 || head[x] > rows + 6) {
                        tail[x] = 0;
                        cooldown[x] = (int) (rnd.nextDouble() * rows * 2.2 / density);
                    }
                } else if (cooldown[x] > 0) {
                    cooldown[x]--;
                } else if (rnd.nextDouble() < 0.6 * density) {
                    // start a new drop; life time scales with speed so it always
                    // makes it across the screen instead of dying off-camera
                    head[x] = -1 - rnd.nextDouble() * 4;
                    speed[x] = 0.5 + rnd.nextDouble() * 1.1;
                    tail[x] = (rows + 8) / speed[x] * (0.75 + rnd.nextDouble() * 0.8);
                    glitchCol[x] = rnd.nextDouble() < glitchChance;
                }
                // occasional glyph mutation keeps the screen alive
                for (int k = 0; k < 2; k++) {
                    int y = rnd.nextInt(rows);
                    if (glow[x][y] > 0.1) {
                        glyph[x][y] = randomGlyph();
                    }
                }
            }
        }

        private char randomGlyph() {
            return ALPHABET.charAt(rnd.nextInt(ALPHABET.length()));
        }

        /** Bright head, fading green tail; a few columns glitch to cyan-white. */
        int[] colorOf(int x, double g) {
            g = Math.pow(g, 0.72); // lift the dim tail so glyphs read as green, not black
            double headBoost = g > 0.70 ? (g - 0.70) / 0.30 : 0;
            headBoost *= headBoost;
            double r;
            double gg;
            double b;
            if (glitchCol[x]) {
                r = 90 * g * g + 165 * headBoost;
                gg = 230 * g + 25 * headBoost;
                b = 245 * g + 10 * headBoost;
            } else {
                r = 30 * g * g * g + 190 * headBoost * headBoost;
                gg = 250 * g;
                b = 60 * g * g * g + 170 * headBoost * headBoost;
            }
            return new int[]{clamp(r), clamp(gg), clamp(b)};
        }

        private static int clamp(double v) {
            return (int) Math.max(0, Math.min(255, v));
        }
    }
}
