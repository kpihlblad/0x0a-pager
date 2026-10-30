package se.grynna.pager.ui.sprattel;

import se.grynna.pager.utils.OKLab;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Generates deterministic Blockies identicons and keeps the rendered tiles in a small LRU cache.
 */
public final class Blockies {

    private static final int DEFAULT_SIZE = 8;
    private static final int DEFAULT_SCALE = 4;
    private static final int DEFAULT_CACHE_SIZE = 256;
    private static final int WARM_UP_DRAWS = 16;
    private static final Blockies DEFAULT_INSTANCE = new Blockies(DEFAULT_CACHE_SIZE);

    private final int maxCacheEntries;
    private final Map<CacheKey, BufferedImage> cache;
    private final Object cacheLock = new Object();

    private Blockies(int cacheSize) {
        if (cacheSize <= 0) {
            throw new IllegalArgumentException("cacheSize must be positive");
        }
        this.maxCacheEntries = cacheSize;
        this.cache = new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, BufferedImage> eldest) {
                return size() > Blockies.this.maxCacheEntries;
            }
        };
    }

    public static Blockies defaultCache() {
        return DEFAULT_INSTANCE;
    }

    public static Blockies withCacheSize(int cacheSize) {
        return new Blockies(cacheSize);
    }

    /**
     * Calculate size and scale to fit exactly within target pixels (rounded down).
     * For example, forPixelSize(32) returns size=8, scale=4 (total 32x32px).
     *
     * @param targetPixels desired width/height in pixels
     * @return BlockiesOptions with size and scale set to fit target exactly
     */
    public static BlockiesOptions forPixelSize(String seed, int targetPixels) {
        int size = DEFAULT_SIZE;
        int scale = Math.max(1, targetPixels / size);
        return BlockiesOptions.of(seed, size, scale);
    }

    /**
     * Calculate pixel dimensions for given options.
     *
     * @param options the blockies configuration
     * @return pixel width/height of the resulting icon
     */
    public static int pixelSize(BlockiesOptions options) {
        int size = options.size > 0 ? options.size : DEFAULT_SIZE;
        int scale = options.scale > 0 ? options.scale : DEFAULT_SCALE;
        return size * scale;
    }

    public BufferedImage icon(String seed) {
        return icon(BlockiesOptions.of(seed));
    }

    public BufferedImage icon(String seed, int size, int scale) {
        return icon(BlockiesOptions.of(seed, size, scale));
    }

    public BufferedImage icon(BlockiesOptions options) {
        ResolvedOptions resolved = resolve(options);
        return cached(resolved.key(), () -> renderIcon(resolved));
    }

    /**
     * Icon for an activity with the spot seed equal to the pattern seed; see
     * {@link #icon(String, String, String, Tone, int, int)}.
     */
    public BufferedImage icon(String colourSeed, String patternSeed, Tone tone, int size, int scale) {
        return icon(colourSeed, patternSeed, patternSeed, tone, size, scale);
    }

    /**
     * Icon for an activity: the background colour comes from {@code colourSeed} (machine and process), the shape and
     * foreground colour from {@code patternSeed} (the same plus the thread), and the spot colour from {@code spotSeed}
     * (the same plus the request). A null or pattern-equal spot seed takes the spot from the pattern stream. A null
     * seed falls back to the other; both null is an error, callers fall back to the message themselves.
     *
     * @param tone {@link Tone#BRIGHT} for structured events, {@link Tone#DIM} for raw ones
     * @param size grid cells per side; 0 applies the default
     * @param scale pixels per cell; 0 applies the default
     */
    public BufferedImage icon(String colourSeed, String patternSeed, String spotSeed, Tone tone, int size,
            int scale) {
        String colour = colourSeed != null ? colourSeed : patternSeed;
        String pattern = patternSeed != null ? patternSeed : colourSeed;
        if (colour == null) {
            throw new IllegalArgumentException("colourSeed and patternSeed are both null");
        }
        Objects.requireNonNull(tone, "tone");
        String spot = spotSeed == null || spotSeed.equals(pattern) ? null : spotSeed;
        ActivityKey key = new ActivityKey(colour, pattern, spot, tone, size > 0 ? size : DEFAULT_SIZE,
                scale > 0 ? scale : DEFAULT_SCALE);
        return cached(key, () -> renderActivityIcon(key));
    }

    public void paint(Graphics2D graphics, BlockiesOptions options, int x, int y) {
        Objects.requireNonNull(graphics, "graphics");
        graphics.drawImage(icon(options), x, y, null);
    }

    private BufferedImage cached(CacheKey key, Supplier<BufferedImage> render) {
        synchronized (cacheLock) {
            BufferedImage cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        BufferedImage rendered = render.get();
        synchronized (cacheLock) {
            cache.put(key, rendered);
        }
        return rendered;
    }

    private static ResolvedOptions resolve(BlockiesOptions options) {
        BlockiesOptions safe = options != null ? options : BlockiesOptions.of(null);

        String seed = safe.seed;
        if (seed == null || seed.isBlank()) {
            seed = randomSeed();
        }

        int size = safe.size > 0 ? safe.size : DEFAULT_SIZE;
        int scale = safe.scale > 0 ? safe.scale : DEFAULT_SCALE;

        BlockiesRandom random = seeded(seed);

        Color color = safe.color != null ? safe.color : createColor(random, Tone.BRIGHT.foreground);
        Color background = safe.backgroundColor != null
                ? safe.backgroundColor
                : createColor(random, Tone.BRIGHT.foreground);
        Color spot = safe.spotColor != null ? safe.spotColor : createColor(random, Tone.BRIGHT.foreground);

        SeedKey key = new SeedKey(seed, size, scale, color, background, spot);
        return new ResolvedOptions(key, random);
    }

    private static BufferedImage renderIcon(ResolvedOptions resolved) {
        SeedKey key = resolved.key();
        int[] imageData = createImageData(resolved.random(), key.size, false);
        return paint(key.size, key.scale, key.background, key.color, key.spotColor, imageData);
    }

    private static BufferedImage renderActivityIcon(ActivityKey key) {
        BlockiesRandom pattern = seeded(key.patternSeed());
        Palette palette = palette(key.colourSeed(), key.patternSeed(), key.spotSeed(), key.tone(), pattern);
        int[] imageData = createImageData(pattern, key.size(), true);
        return paint(key.size(), key.scale(), palette.background(), palette.color(), palette.spot(), imageData);
    }

    private static BufferedImage paint(int size, int scale, Color background, Color color, Color spotColor,
            int[] imageData) {
        int tileSize = size * scale;
        BufferedImage image = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setColor(background);
            g2d.fillRect(0, 0, tileSize, tileSize);

            for (int i = 0; i < imageData.length; i++) {
                int value = imageData[i];
                if (value == 0) {
                    continue;
                }
                int row = i / size;
                int col = i % size;
                g2d.setColor(value == 1 ? color : spotColor);
                g2d.fillRect(col * scale, row * scale, scale, scale);
            }
        } finally {
            g2d.dispose();
        }
        return image;
    }

    /**
     * Background from the colour seed; colour and spot from the pattern seed. The pattern stream draws the background
     * too (discarded), so the foreground and spot draws keep the same positions as before. A spot seed other than the
     * pattern seed takes the spot from its own stream; the pattern stream still makes that draw, so the shape does not
     * depend on the spot seed.
     */
    static Palette palette(String colourSeed, String patternSeed, Tone tone) {
        return palette(colourSeed, patternSeed, null, tone);
    }

    static Palette palette(String colourSeed, String patternSeed, String spotSeed, Tone tone) {
        return palette(colourSeed, patternSeed, spotSeed, tone, seeded(patternSeed));
    }

    private static Palette palette(String colourSeed, String patternSeed, String spotSeed, Tone tone,
            BlockiesRandom pattern) {
        Color background = createColor(seeded(colourSeed), tone.background);
        Color color = createColor(pattern, tone.foreground);
        createColor(pattern, tone.background);
        Color spot = createColor(pattern, tone.foreground);
        if (spotSeed != null && !spotSeed.equals(patternSeed)) {
            spot = createColor(seeded(spotSeed), tone.foreground);
        }
        return new Palette(background, color, spot);
    }

    /**
     * Cell values 0 (background), 1 (colour), 2 (spot). Activity icons draw in [0, 1): ~43 % background, which carries
     * the process colour. Legacy icons keep the original [0, 2) draw (mostly spot).
     */
    private static int[] createImageData(BlockiesRandom random, int size, boolean unitDraws) {
        int[] imageData = new int[size * size];
        int dataWidth = (int) Math.ceil(size / 2.0);
        int rowOffset = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < dataWidth; x++) {
                int value = (int) Math.floor((unitDraws ? unit(random) : random.next()) * 2.3);
                int mirror = size - x - 1;
                imageData[rowOffset + x] = value;
                imageData[rowOffset + mirror] = value;
            }
            rowOffset += size;
        }
        return imageData;
    }

    private static Color createColor(BlockiesRandom random, Range range) {
        // Generate hue (0-360 degrees, converted to radians for OKLCH)
        double hueDegrees = unit(random) * 360;
        float hueRadians = (float) Math.toRadians(hueDegrees);

        float lightness = (float) (range.lightMin + unit(random) * range.lightSpan);

        float chroma = (float) (range.chromaMin + unit(random) * range.chromaSpan);

        OKLab.LCH lch = new OKLab.LCH(lightness, chroma, hueRadians);

        // Map to gamut to ensure valid RGB values
        lch = OKLab.mapToGamut(lch);

        return OKLab.oklchToColor(lch);
    }

    /** The seeding only mixes the seed into the state; the first outputs barely depend on its tail, so warm up. */
    private static BlockiesRandom seeded(String seed) {
        BlockiesRandom random = new BlockiesRandom();
        random.seed(seed);
        for (int i = 0; i < WARM_UP_DRAWS; i++) {
            random.next();
        }
        return random;
    }

    /** A draw in [0, 1); {@link BlockiesRandom#next()} returns [0, 2). */
    private static double unit(BlockiesRandom random) {
        return random.next() / 2;
    }

    private static String randomSeed() {
        long randomLong = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        return Long.toHexString(randomLong);
    }

    /** Colour lightness and chroma ranges: lightness in [min, min + span), chroma likewise. */
    private record Range(double lightMin, double lightSpan, double chromaMin, double chromaSpan) {}

    /**
     * Palette tone. BRIGHT is for structured (JSON) events; DIM, lower in lightness and chroma, is for raw events.
     */
    public enum Tone {
        BRIGHT(new Range(0.45, 0.10, 0.10, 0.08), new Range(0.55, 0.20, 0.10, 0.08)), DIM(
                new Range(0.30, 0.08, 0.05, 0.05), new Range(0.38, 0.12, 0.05, 0.05));

        private final Range background;
        private final Range foreground;

        Tone(Range background, Range foreground) {
            this.background = background;
            this.foreground = foreground;
        }
    }

    record Palette(Color background, Color color, Color spot) {}

    private sealed interface CacheKey permits SeedKey, ActivityKey {}

    private record SeedKey(String seed, int size, int scale, Color color, Color background, Color spotColor)
            implements CacheKey {}

    private record ActivityKey(String colourSeed, String patternSeed, String spotSeed, Tone tone, int size, int scale)
            implements CacheKey {}

    private record ResolvedOptions(SeedKey key, BlockiesRandom random) {}

    private static final class BlockiesRandom {
        private final int[] state = new int[4];

        void seed(String seed) {
            for (int i = 0; i < state.length; i++) {
                state[i] = 0;
            }
            for (int i = 0; i < seed.length(); i++) {
                int value = seed.charAt(i);
                int index = i & 3;
                state[index] = (state[index] << 5) - state[index] + value;
            }
        }

        double next() {
            int t = state[0] ^ (state[0] << 11);
            state[0] = state[1];
            state[1] = state[2];
            state[2] = state[3];
            state[3] = state[3] ^ (state[3] >>> 19) ^ t ^ (t >>> 8);
            long unsigned = state[3] & 0xffffffffL;
            return unsigned / 2147483648d;
        }
    }

    /**
     * Configuration for generating a Blockies icon.
     * Use 0 for size/scale to apply defaults.
     */
    public record BlockiesOptions(String seed, int size, int scale, Color color, Color backgroundColor,
            Color spotColor) {
        public static BlockiesOptions of(String seed) {
            return new BlockiesOptions(seed, 0, 0, null, null, null);
        }

        public static BlockiesOptions of(String seed, int size, int scale) {
            return new BlockiesOptions(seed, size, scale, null, null, null);
        }

        public BlockiesOptions withColors(Color color, Color backgroundColor, Color spotColor) {
            return new BlockiesOptions(seed, size, scale, color, backgroundColor, spotColor);
        }
    }
}
