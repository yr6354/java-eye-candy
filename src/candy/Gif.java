package candy;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.util.Iterator;

/**
 * Minimal animated-GIF writer built on the JDK's own GIF encoder.
 * Everything here is pure JDK: no third-party dependency at all.
 *
 * 纯 JDK 实现的动图导出：内部使用 6x6x6 web-safe 调色板 + 4x4 Bayer 抖动，
 * 对荧光/星云这类画面效果很好，而且体积可控。
 */
public final class Gif {

    private Gif() {
    }

    private static final int LEVELS = 6;
    private static final int[] LEVEL_VALUE = {0, 51, 102, 153, 204, 255};

    /** 6x6x6 web-safe cube: 216 colors, padded to 256 entries (GIF needs a power of two). */
    public static final IndexColorModel PALETTE = buildWebSafePalette();

    private static final int[] LUT = new int[32 * 32 * 32];
    private static final int[] BAYER = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
    };

    private static IndexColorModel buildWebSafePalette() {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        int i = 0;
        for (int ri = 0; ri < LEVELS; ri++) {
            for (int gi = 0; gi < LEVELS; gi++) {
                for (int bi = 0; bi < LEVELS; bi++) {
                    r[i] = (byte) LEVEL_VALUE[ri];
                    g[i] = (byte) LEVEL_VALUE[gi];
                    b[i] = (byte) LEVEL_VALUE[bi];
                    i++;
                }
            }
        }
        // 216..255: a smooth grayscale tail, handy for glow falloff.
        for (int k = 0; k < 40; k++, i++) {
            int v = (int) Math.round(k * 255.0 / 39.0);
            r[i] = (byte) v;
            g[i] = (byte) v;
            b[i] = (byte) v;
        }
        return new IndexColorModel(8, 256, r, g, b);
    }

    /** Nearest palette index for an 8-bit RGB triple (5-bit per channel cache). */
    public static int index(int r, int g, int b) {
        int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
        int hit = LUT[key];
        if (hit != 0) {
            return hit - 1;
        }
        int cr = (((r >> 3) << 3) | 4) & 0xFF;
        int cg = (((g >> 3) << 3) | 4) & 0xFF;
        int cb = (((b >> 3) << 3) | 4) & 0xFF;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            int dr = cr - (PALETTE.getRed(i) & 0xFF);
            int dg = cg - (PALETTE.getGreen(i) & 0xFF);
            int db = cb - (PALETTE.getBlue(i) & 0xFF);
            long d = (long) dr * dr * 3 + (long) dg * dg * 6 + (long) db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        LUT[key] = best + 1;
        return best;
    }

    /** Create a blank indexed frame that shares our palette. */
    public static BufferedImage frame(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, PALETTE);
    }

    /**
     * Copy an ARGB raster into an indexed frame, with ordered dithering so that
     * dark gradients (space, glow) do not band too badly.
     */
    public static void blit(BufferedImage indexed, int[] argb, int w, int h) {
        WritableRaster raster = indexed.getRaster();
        boolean tight = raster.getSampleModel() instanceof ComponentSampleModel csm
                && csm.getScanlineStride() == w
                && csm.getPixelStride() == 1
                && csm.getBandOffsets()[0] == 0
                && raster.getDataBuffer() instanceof DataBufferByte;
        if (tight && raster.getDataBuffer() instanceof DataBufferByte buf) {
            byte[] data = buf.getData();
            int base = buf.getOffset();
            int p = 0;
            for (int y = 0; y < h; y++) {
                int rowBase = base + y * w;
                for (int x = 0; x < w; x++, p++) {
                    int c = argb[p];
                    int d = BAYER[((y & 3) << 2) | (x & 3)] - 8;
                    int r = clamp8(((c >> 16) & 0xFF) + (d * 26 >> 4));
                    int g = clamp8(((c >> 8) & 0xFF) + (d * 26 >> 4));
                    int b = clamp8((c & 0xFF) + (d * 26 >> 4));
                    data[rowBase + x] = (byte) index(r, g, b);
                }
            }
        } else {
            int p = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++, p++) {
                    int c = argb[p];
                    raster.setSample(x, y, 0, index((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF));
                }
            }
        }
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Convenience: ARGB pixels -> PNG. */
    public static void writePng(File out, int[] argb, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", out);
    }

    /**
     * Write frames as a looping animated GIF.
     *
     * @param delayMs delay between frames, in milliseconds
     */
    public static void writeAnimated(File out, java.util.List<BufferedImage> frames, int delayMs) throws Exception {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("no frames");
        }
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("gif");
        if (!it.hasNext()) {
            throw new IllegalStateException("this JRE has no GIF writer");
        }
        ImageWriter writer = it.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        String format = writer.getOriginatingProvider().getNativeImageMetadataFormatName();

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            boolean first = true;
            for (BufferedImage frame : frames) {
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frame), param);
                configure(meta, format, delayMs, first);
                writer.writeToSequence(new IIOImage(frame, null, meta), param);
                first = false;
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static void configure(IIOMetadata meta, String format, int delayMs, boolean first)
            throws Exception {
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", String.valueOf(Math.max(2, Math.round(delayMs / 10f))));
        gce.setAttribute("transparentColorIndex", "0");

        if (first) {
            IIOMetadataNode appExtensions = child(root, "ApplicationExtensions");
            IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[]{0x1, 0x0, 0x0}); // loop forever
            appExtensions.appendChild(loop);
        }
        meta.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }
}
