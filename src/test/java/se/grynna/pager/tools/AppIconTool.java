package se.grynna.pager.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dev tool, not a test: draws the application icon, "0x" in ANSI Shadow block letters set in the bundled
 * Monaspace font, and writes the window icons ({@code src/main/resources/icons/0x0a-pager-<size>.png}) and the
 * Windows launcher icon ({@code src/main/jpackage/0x0a-pager.ico}). Run from the repository root:
 *
 * <pre>
 * mvn test -Dtest=AppIconTool -Dtest.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("tool")
class AppIconTool {

    /** Window icon sizes; the window manager or taskbar picks the closest one. */
    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 256};
    /** Up to this size the block letters are drawn pixel-exact, one pixel per column and two per row. */
    private static final int GRID_MAX = 24;
    /** Up to this size the shadow strokes are left out; they blur into grey at small sizes. */
    private static final int BLOCKS_ONLY_MAX = 32;
    private static final int MASTER = 1024;
    private static final Color BACKGROUND = new Color(0x282a36);
    private static final Color FOREGROUND = new Color(0xf8f8f2);

    private static final String[] ART = {
            " ██████╗ ██╗  ██╗",
            "██╔═████╗╚██╗██╔╝",
            "██║██╔██║ ╚███╔╝ ",
            "████╔╝██║ ██╔██╗ ",
            "╚██████╔╝██╔╝ ██╗",
            " ╚═════╝ ╚═╝  ╚═╝"};

    /** "0x" for the 16 px icon, where the block letters do not fit. */
    private static final String[] PIXELS_16 = {
            ".####...##..##",
            "##..##..##..##",
            "##.###...####.",
            "###.##....##..",
            "##..##...####.",
            "##..##..##..##",
            ".####...##..##"};

    @Test
    void render() throws Exception {
        Font font;
        try (InputStream in = Objects.requireNonNull(
                AppIconTool.class.getResourceAsStream("/fonts/MonaspaceNeonNF-Regular.otf"))) {
            font = Font.createFont(Font.TRUETYPE_FONT, in);
        }
        BufferedImage full = master(font, false);
        BufferedImage blocks = master(font, true);

        Path pngDir = Path.of("src/main/resources/icons");
        Files.createDirectories(pngDir);
        List<byte[]> icoImages = new ArrayList<>();
        List<Integer> icoSizes = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage icon;
            if (size == 16) {
                icon = pixels(size, PIXELS_16, 1, 1);
            } else if (size <= GRID_MAX) {
                icon = pixels(size, blockGrid(), 1, 2);
            } else {
                icon = scale(size <= BLOCKS_ONLY_MAX ? blocks : full, size);
            }
            ImageIO.write(icon, "png", pngDir.resolve("0x0a-pager-" + size + ".png").toFile());
            for (int icoSize : ICO_SIZES) {
                if (icoSize == size) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ImageIO.write(icon, "png", bytes);
                    icoImages.add(bytes.toByteArray());
                    icoSizes.add(size);
                }
            }
        }
        Path ico = Path.of("src/main/jpackage/0x0a-pager.ico");
        Files.createDirectories(ico.getParent());
        writeIco(ico.toFile(), icoSizes, icoImages);
    }

    private static BufferedImage master(Font base, boolean blocksOnly) {
        BufferedImage img = new BufferedImage(MASTER, MASTER, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, MASTER, MASTER, MASTER / 6, MASTER / 6);

        int columns = ART[0].length();
        g.setFont(base.deriveFont(100f));
        FontMetrics probe = g.getFontMetrics();
        double scale = Math.min(MASTER * 0.84 / (probe.charWidth('█') * columns),
                MASTER * 0.84 / ((probe.getAscent() + probe.getDescent()) * ART.length));
        g.setFont(base.deriveFont((float) (100 * scale)));
        FontMetrics fm = g.getFontMetrics();
        double cellWidth = fm.charWidth('█');
        double lineHeight = fm.getAscent() + fm.getDescent();
        double x0 = (MASTER - cellWidth * columns) / 2;
        double y0 = (MASTER - lineHeight * ART.length) / 2;

        g.setColor(FOREGROUND);
        for (int row = 0; row < ART.length; row++) {
            String line = blocksOnly ? ART[row].replaceAll("[^█]", " ") : ART[row];
            g.drawString(line, (float) x0, (float) (y0 + row * lineHeight + fm.getAscent()));
        }
        g.dispose();
        return img;
    }

    /** The block cells of {@link #ART} as a bitmap ({@code #} set), trimmed to the columns that hold blocks. */
    private static String[] blockGrid() {
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (String line : ART) {
            int from = line.indexOf('█');
            if (from >= 0) {
                first = Math.min(first, from);
                last = Math.max(last, line.lastIndexOf('█'));
            }
        }
        String[] grid = new String[ART.length];
        for (int row = 0; row < ART.length; row++) {
            grid[row] = ART[row].substring(first, last + 1).replaceAll("[^█]", ".").replace('█', '#');
        }
        return grid;
    }

    /** Draws a bitmap centred on the icon background, each {@code #} as a {@code cellWidth} x {@code cellHeight} block. */
    private static BufferedImage pixels(int size, String[] bitmap, int cellWidth, int cellHeight) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, size, size, size / 6, size / 6);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(FOREGROUND);
        int x0 = (size - bitmap[0].length() * cellWidth) / 2;
        int y0 = (size - bitmap.length * cellHeight) / 2;
        for (int row = 0; row < bitmap.length; row++) {
            for (int col = 0; col < bitmap[row].length(); col++) {
                if (bitmap[row].charAt(col) == '#') {
                    g.fillRect(x0 + col * cellWidth, y0 + row * cellHeight, cellWidth, cellHeight);
                }
            }
        }
        g.dispose();
        return img;
    }

    /** Halves with bilinear filtering until close, then scales to the exact size, to avoid aliasing. */
    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage img = src;
        while (img.getWidth() / 2 >= size) {
            img = resize(img, img.getWidth() / 2);
        }
        return img.getWidth() == size ? img : resize(img, size);
    }

    private static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return dst;
    }

    /** Writes an ICO container with PNG-compressed entries (supported since Windows Vista). */
    private static void writeIco(File file, List<Integer> sizes, List<byte[]> pngs) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            writeShortLe(out, 0);
            writeShortLe(out, 1);
            writeShortLe(out, sizes.size());
            int offset = 6 + 16 * sizes.size();
            for (int i = 0; i < sizes.size(); i++) {
                int size = sizes.get(i);
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(0);
                out.writeByte(0);
                writeShortLe(out, 1);
                writeShortLe(out, 32);
                writeIntLe(out, pngs.get(i).length);
                writeIntLe(out, offset);
                offset += pngs.get(i).length;
            }
            for (byte[] png : pngs) {
                out.write(png);
            }
        }
    }

    private static void writeShortLe(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
    }

    private static void writeIntLe(DataOutputStream out, int value) throws IOException {
        writeShortLe(out, value & 0xffff);
        writeShortLe(out, value >>> 16);
    }
}
