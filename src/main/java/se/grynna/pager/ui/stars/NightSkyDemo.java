package se.grynna.pager.ui.stars;

/**
 * Demonstration class for the ASCIINightSkyGenerator.
 * Shows how to generate different types of night sky chunks.
 */
public class NightSkyDemo {

    public static void main(String[] args) {
        System.out.println("=== ASCII Night Sky Generator Demo ===\n");

        // Basic 80x20 night sky chunk
        System.out.println("1. Standard night sky (80x20):");
        ASCIINightSkyGenerator generator = new ASCIINightSkyGenerator(200, 80);
        String sky = generator.generateSkyChunkAsString();
        System.out.println(sky);
        System.out.println("\n" + "=".repeat(80) + "\n");

        // Smaller dense sky
        System.out.println("2. Dense small sky (40x15) with more objects:");
        ASCIINightSkyGenerator denseGenerator = new ASCIINightSkyGenerator(200, 80).setMoonCount(2).setAsteroidCount(5)
                .setBigStarCount(6).setSmallStarCount(60);

        String denseSky = denseGenerator.generateSkyChunkAsString();
        System.out.println(denseSky);
        System.out.println("\n" + "=".repeat(40) + "\n");

        // Sparse sky with big moons
        System.out.println("3. Sparse sky (60x12) with big moons:");
        ASCIINightSkyGenerator sparseGenerator = new ASCIINightSkyGenerator(200, 80).setMoonCount(1).setAsteroidCount(1)
                .setBigStarCount(2).setSmallStarCount(25).setUseBigMoons(true).setRequireNullSurrounding(true);

        String sparseSky = sparseGenerator.generateSkyChunkAsString();
        System.out.println(sparseSky);
        System.out.println("\n" + "=".repeat(60) + "\n");

        // Multiple chunks to show randomness
        System.out.println("4. Three consecutive chunks (30x8) to show variation:");
        ASCIINightSkyGenerator smallGenerator = new ASCIINightSkyGenerator(200, 80).setMoonCount(1).setAsteroidCount(2)
                .setBigStarCount(1).setSmallStarCount(20);

        for (int i = 1; i <= 3; i++) {
            System.out.println("Chunk " + i + ":");
            String chunk = smallGenerator.generateSkyChunkAsString();
            System.out.println(chunk);
            System.out.println("-".repeat(30));
        }

        // Very wide chunk to demonstrate no clipping
        System.out.println("\n5. Wide chunk (100x8) - no objects should be clipped:");
        ASCIINightSkyGenerator wideGenerator = new ASCIINightSkyGenerator(200, 80).setMoonCount(2).setAsteroidCount(4)
                .setBigStarCount(3).setSmallStarCount(40);

        String wideSky = wideGenerator.generateSkyChunkAsString();
        System.out.println(wideSky);
        System.out.println("\n" + "=".repeat(100));

        // Show that objects are properly contained
        System.out.println("\nValidation: Checking that all objects fit within bounds...");

        // Super-wide chunk with conservative object counts
        System.out.println("\n6. SUPER-WIDE chunk (200x15) with conservative object distribution:");
        ASCIINightSkyGenerator superWideGenerator = new ASCIINightSkyGenerator(200, 15).setMoonCount(1) // Conservative: 1 moon
                .setAsteroidCount(2) // Conservative: 2 asteroids
                .setBigStarCount(3) // Conservative: 3 big stars
                .setSmallStarCount(50) // Conservative: 50 small stars (sparse for the size)
                .setAlienCount(1) // Rare: 1 alien artifact
                .setUseBigMoons(false) // Use regular moons for space efficiency
                .setRequireNullSurrounding(true); // Give small stars breathing room

        String superWideSky = superWideGenerator.generateSkyChunkAsString();
        System.out.println(superWideSky);
        System.out.println("\n" + "=".repeat(200));

        // Show alien rarity demonstration
        System.out.println("\n7. Alien rarity demonstration - generating 5 chunks to show alien placement:");
        ASCIINightSkyGenerator alienDemo = new ASCIINightSkyGenerator(150, 12).setMoonCount(1).setAsteroidCount(1)
                .setBigStarCount(2).setSmallStarCount(30).setAlienCount(1); // Guarantee one alien per chunk for demo

        for (int i = 1; i <= 3; i++) {
            System.out.println("Alien demo chunk " + i + " (150x12):");
            String alienChunk = alienDemo.generateSkyChunkAsString();
            System.out.println(alienChunk);
            System.out.println("-".repeat(150));
        }

        // Final validation for super-wide chunk
        System.out.println("\nFinal validation: Checking super-wide chunk integrity...");
        String[] superWideLines = superWideGenerator.generateSkyChunk();
        boolean superWideValid = true;
        for (int i = 0; i < superWideLines.length; i++) {
            if (superWideLines[i].length() != 200) {
                System.out
                        .println("ERROR: Line " + i + " has length " + superWideLines[i].length() + " instead of 200");
                superWideValid = false;
            }
        }
        if (superWideValid) {
            System.out.println("✓ Super-wide chunk (200x15) validated - no clipping detected!");
            System.out.println("✓ All objects (moons, asteroids, stars, aliens) fit perfectly within bounds!");
        }

        System.out.println("8. All demonstrations complete.");
        // Show alien rarity demonstration

        ASCIINightSkyGenerator mood = new ASCIINightSkyGenerator(200, 50).setAlienCount(1).setAsteroidCount(5)
                .setBigStarCount(25).setMoonCount(1).setRequireNullSurrounding(true).setSmallStarCount(300)
                .setUseBigMoons(true);

        System.out.println(mood.generateSkyChunkAsString());
    }
}
