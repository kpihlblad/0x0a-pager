package se.grynna.pager.ui.stars;

import java.util.Arrays;
import java.util.Random;

/**
 * A Java implementation of an ASCII night sky generator inspired by the JavaScript version.
 * Generates chunks of ASCII art representing a night sky with stars, moons, and asteroids.
 * Ensures no objects are clipped by validating placement before adding to the sky matrix.
 */
public class ASCIINightSkyGenerator {

    private final Random random;
    private int width;
    private int height;
    private String[][] skyMatrix;

    // Small star characters and their probabilities
    private final String[] smallStars = {".", "+", "*", "o", "'", "✦", "★"};
    private final double[] smallStarProbabilities = {0.34, 0.17, 0.10, 0.10, 0.22, 0.05, 0.02};

    // Multi-line ASCII art for bigger objects (♦ represents non-transparent whitespace that cannot overlap)
    private final String[] bigStars = {"""
            ♦|♦
            -o-
            ♦|♦""", """
            ♦♦|♦♦
            --o--
            ♦♦|♦♦""", """
            _|_
            ♦|♦""", """
            ♦|♦
            -+-
            ♦|♦""", """
            ♦♦|♦♦
            -♦o♦-
            ♦♦|♦♦""", """
            ♦♦|♦♦
            -♦*♦-
            ♦♦|♦♦"""};

    private final String[] asteroids = {"~~+", """
            \\♦♦
            ♦\\♦
            ♦♦*""", """
            ':.♦♦♦♦♦
            ♦♦'::._♦
            ♦♦♦♦'._)""", "+~~", """
            ♦♦/
            ♦/♦
            *♦♦""", """
            ♦♦♦♦♦.:'
            ♦_.::'♦♦
            (_.'♦♦♦♦"""};

    private final String[] moons = {"""
            .-.♦
            ♦)♦)
            '-´♦""", """
            ♦.-.♦
            (♦♦♦)
            ♦`-'♦""", """
            ♦.-.
            (♦(♦
            ♦`-'""", """
            ☾"""};

    private final String[] bigMoons = {"""
            ♦♦_..♦♦
            '`-.♦`.
            ♦♦♦\\♦♦\\
            ♦♦♦|♦♦|
            ♦♦♦/♦♦/
            _.-`_.`
            ♦'''♦♦♦""", """
            ♦♦♦.._♦♦
            ♦.'♦.-'`
            /♦♦/♦♦♦♦
            |♦♦|♦♦♦♦
            \\♦♦\\♦♦♦♦
            ♦'._'-._
            ♦♦♦♦```♦"""};

    private final String[] planets = {"""
            ♦♦♦♦.-'""'-.♦♦♦♦
            ♦♦.'♦()♦.♦♦♦'.♦♦
            ♦/♦.♦♦♦♦♦♦o♦♦♦\\♦
            ;♦o♦♦♦♦_♦♦♦()♦♦;
            ;♦♦♦♦♦(_)♦♦♦♦♦♦;
            ♦\\♦.♦♦♦♦♦♦♦♦o♦/♦
            ♦♦'.♦♦O♦♦.♦♦.'♦♦
            ♦♦♦♦'-....-'♦♦♦♦""", """
            ♦♦♦♦♦.---.♦♦♦♦♦
            ♦♦♦.'♦♦♦♦♦'.♦♦♦
            _.-'♦♦♦o♦♦♦'-._
            '--.._____..--'
            ♦♦♦'.♦♦♦♦♦.'♦♦♦
            ♦♦♦♦♦'---'♦♦♦♦♦"""};

    // Rare alien artifact - very uncommon
    private final String[] aliens = {"""
            ♦♦ʔ♦♦ʕ♦♦♦
            (◕♦‿♦◕♦✿)
            ☜[♦♡♦♦♦]☞
            ♦♦~~~~~♦♦
            ♦♦♦◡♦◡♦♦♦"""};

    // Subtle cosmic surprises (UFO, satellite, tiny rocket)
    private final String[] surprises = {"""
            ♦♦_.---._♦♦
            .'♦♦○♦○♦♦'.
            `~-.-~-.-~'""", """
            [::]-o-[::]""", """
            ♦/^\\♦
            |-o-|
            /___\\
            ♦vvv♦"""};

    // Configuration options
    private int moonCount = 1;
    private int asteroidCount = 3;
    private int bigStarCount = 3;
    private int smallStarCount = 80;
    private int alienCount = 0; // Aliens are rare, default to 0
    private int surpriseCount = 0;
    private boolean requireNullSurrounding = true;
    private boolean useBigMoons = false;

    /**
     * Creates a new ASCII night sky generator with specified dimensions.
     *
     * @param width  Width of the sky chunk (minimum 10)
     * @param height Height of the sky chunk (minimum 5)
     */
    public ASCIINightSkyGenerator(int width, int height) {
        this(width, height, new Random());
    }

    public ASCIINightSkyGenerator(int width, int height, Random random) {
        this.width = Math.max(width, 10);
        this.height = Math.max(height, 5);
        this.random = random;
    }

    /**
     * Generates a new random chunk of ASCII night sky.
     *
     * @return String array representing the night sky, where each element is a line
     */
    public String[] generateSkyChunk() {
        initializeSky();
        fillSky();
        return convertMatrixToStringArray();
    }

    /**
     * Generates a new random chunk as a single string with newlines.
     *
     * @return Single string with the entire night sky
     */
    public String generateSkyChunkAsString() {
        String[] lines = generateSkyChunk();
        return String.join("\n", lines);
    }

    /**
     * Initialize the sky matrix with null values.
     */
    private void initializeSky() {
        skyMatrix = new String[height][width];
        for (int y = 0; y < height; y++) {
            Arrays.fill(skyMatrix[y], null);
        }
    }

    /**
     * Get a random small star based on probabilities.
     */
    private String getRandomSmallStar() {
        double rand = random.nextDouble();
        double cumulative = 0.0;

        for (int i = 0; i < smallStars.length; i++) {
            cumulative += smallStarProbabilities[i];
            if (rand < cumulative) {
                return smallStars[i];
            }
        }
        return smallStars[smallStars.length - 1];
    }

    /**
     * Check if a position has null surrounding cells (for small star spacing).
     */
    private boolean hasNullSurrounding(int x, int y) {
        int[][] offsets = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

        for (int[] offset : offsets) {
            int nx = x + offset[0];
            int ny = y + offset[1];

            if (nx >= 0 && nx < width && ny >= 0 && ny < height && skyMatrix[ny][nx] != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Try to place an object (string with possible newlines) in the sky.
     * Attempts up to 20 random positions to find a valid placement.
     */
    private boolean placeObject(String objectStr) {
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);

            // Check if small star needs surrounding space
            if (requireNullSurrounding && isSmallStar(objectStr) && !hasNullSurrounding(x, y)) {
                continue;
            }

            String[] lines = objectStr.split("\n");
            if (canPlaceObject(lines, x, y)) {
                applyObjectToMatrix(lines, x, y);
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a string is a small star.
     */
    private boolean isSmallStar(String str) {
        return Arrays.asList(smallStars).contains(str);
    }

    /**
     * Check if an object can be placed at the given position without clipping or overlapping.
     */
    private boolean canPlaceObject(String[] lines, int x, int y) {
        // Check if object fits within bounds
        if (y + lines.length > height) {
            return false;
        }

        for (int i = 0; i < lines.length; i++) {
            if (x + lines[i].length() > width) {
                return false;
            }

            // Check for overlaps - ♦ characters represent non-transparent whitespace that cannot overlap
            for (int j = 0; j < lines[i].length(); j++) {
                char ch = lines[i].charAt(j);
                if (ch != ' ') {
                    if (skyMatrix[y + i][x + j] != null) {
                        return false;
                    }
                    if (requireNullSurrounding && !hasNullSurrounding(x + j, y + i)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Apply an object to the sky matrix at the given position.
     */
    private void applyObjectToMatrix(String[] lines, int x, int y) {
        for (int i = 0; i < lines.length; i++) {
            for (int j = 0; j < lines[i].length(); j++) {
                char ch = lines[i].charAt(j);
                if (ch != ' ') { // Place all non-space characters (including ♦)
                    skyMatrix[y + i][x + j] = String.valueOf(ch);
                }
            }
        }
    }

    /**
     * Fill the sky with various celestial objects.
     */
    private void fillSky() {
        // Place larger objects first to avoid blocking
        if (useBigMoons && moonCount > 0) {
            if (moonCount == 1) {
                placeFromArray(random.nextBoolean() ? bigMoons : planets, 1);
            } else {
                placeFromArray(bigMoons, 1);
                placeFromArray(planets, 1);
                if (moonCount > 2) {
                    placeFromArray(moons, moonCount - 2);
                }
            }
        } else {
            placeFromArray(moons, moonCount);
        }
        placeFromArray(aliens, alienCount);
        placeFromArray(surprises, surpriseCount);
        placeFromArray(asteroids, asteroidCount);
        placeFromArray(bigStars, bigStarCount);

        // Place small stars last
        placeSmallStars(smallStarCount);

        // Fill remaining null positions with spaces
        fillNullWithWhitespace();
    }

    /**
     * Place multiple objects from an array.
     */
    private void placeFromArray(String[] objects, int count) {
        for (int i = 0; i < count; i++) {
            String object = objects[random.nextInt(objects.length)];
            placeObject(object);
        }
    }

    /**
     * Place small stars using probability distribution.
     */
    private void placeSmallStars(int count) {
        for (int i = 0; i < count; i++) {
            String star = getRandomSmallStar();
            placeObject(star);
        }
    }

    /**
     * Fill all null positions with spaces.
     */
    private void fillNullWithWhitespace() {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (skyMatrix[y][x] == null || "♦".equals(skyMatrix[y][x])) {
                    skyMatrix[y][x] = " ";
                }
            }
        }
    }

    /**
     * Convert the sky matrix to a string array.
     */
    private String[] convertMatrixToStringArray() {
        String[] result = new String[height];
        for (int y = 0; y < height; y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < width; x++) {
                line.append(skyMatrix[y][x]);
            }
            result[y] = line.toString();
        }
        return result;
    }

    // Configuration methods

    /**
     * Set the number of moons to generate (0-100).
     */
    public ASCIINightSkyGenerator setMoonCount(int count) {
        this.moonCount = Math.max(0, Math.min(100, count));
        return this;
    }

    /**
     * Set the number of asteroids to generate (0-50).
     */
    public ASCIINightSkyGenerator setAsteroidCount(int count) {
        this.asteroidCount = Math.max(0, Math.min(50, count));
        return this;
    }

    /**
     * Set the number of big stars to generate (0-100).
     */
    public ASCIINightSkyGenerator setBigStarCount(int count) {
        this.bigStarCount = Math.max(0, Math.min(100, count));
        return this;
    }

    /**
     * Set the number of small stars to generate (0-2000).
     */
    public ASCIINightSkyGenerator setSmallStarCount(int count) {
        this.smallStarCount = Math.max(0, Math.min(2000, count));
        return this;
    }

    /**
     * Set the number of aliens to generate (0-20).
     */
    public ASCIINightSkyGenerator setAlienCount(int count) {
        this.alienCount = Math.max(0, Math.min(20, count));
        return this;
    }

    /**
     * Set the number of cosmic surprises (UFO, satellite, rocket) to generate (0-20).
     */
    public ASCIINightSkyGenerator setSurpriseCount(int count) {
        this.surpriseCount = Math.max(0, Math.min(20, count));
        return this;
    }

    /**
     * Set whether small stars require surrounding empty space.
     */
    public ASCIINightSkyGenerator setRequireNullSurrounding(boolean require) {
        this.requireNullSurrounding = require;
        return this;
    }

    /**
     * Set whether to use big moons instead of regular moons.
     */
    public ASCIINightSkyGenerator setUseBigMoons(boolean useBig) {
        this.useBigMoons = useBig;
        return this;
    }

    /**
     * Set custom dimensions for the sky.
     */
    public ASCIINightSkyGenerator setDimensions(int width, int height) {
        this.width = Math.max(width, 10);
        this.height = Math.max(height, 5);
        return this;
    }

    // Getters
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getMoonCount() {
        return moonCount;
    }

    public int getAsteroidCount() {
        return asteroidCount;
    }

    public int getBigStarCount() {
        return bigStarCount;
    }

    public int getSmallStarCount() {
        return smallStarCount;
    }

    public int getAlienCount() {
        return alienCount;
    }
}
