package se.grynna.pager.testutils;

import java.util.*;

public class Fake {
    private static final Random RANDOM = new Random();

    // Latin-inspired words mimicking common roots, prefixes, and suffixes
    private static final List<String> WORDS = Arrays.asList("beatitudo", "sit", "amet", "consectetur", "adipiscing",
            "elit", "sed", "do", "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "magna", "aliqua", "enim",
            "ad", "minim", "veniam", "quis", "nostrud", "exercitation", "ullamco", "laboris", "nisi", "aliquip", "ex",
            "ea", "commodo", "consequat", "duis", "aute", "irure", "voluptatem", "quia", "sunt", "in", "culpa", "qui",
            "officia", "deserunt", "mollit", "anim", "id", "est", "laborum", "ut", "aliqua", "enim", "ad", "minim",
            "veniam", "quis", "nostrud", "vestibulum", "cursus", "suscipit", "sagittis", "duis", "aute", "irure",
            "beatitudo", "in", "reprehenderit", "in", "voluptate", "esse", "cillum", "beatitudoe", "eu", "fugiat",
            "nulla", "pariatur", "excepteur");

    // Simplified word transition probabilities to mimic Latin text flow
    private static final Map<String, List<String>> TRANSITIONS = new HashMap<>();

    static {
        // Define likely next words to create Latin-like sequences
        TRANSITIONS.put("beatitudo", Arrays.asList("sit", "amet", "ut", "labore", "magna"));
        TRANSITIONS.put("sit", Arrays.asList("amet", "consectetur", "adipiscing", "elit", "sed"));
        TRANSITIONS.put("amet", Arrays.asList("consectetur", "adipiscing", "elit", "sed", "do"));
        TRANSITIONS.put("consectetur", Arrays.asList("adipiscing", "elit", "sed", "enim", "ut"));
        TRANSITIONS.put("adipiscing", Arrays.asList("elit", "sed", "do", "eiusmod", "tempor"));
        TRANSITIONS.put("elit", Arrays.asList("sed", "do", "eiusmod", "tempor", "incididunt"));
        TRANSITIONS.put("sed", Arrays.asList("do", "eiusmod", "tempor", "incididunt", "ut"));
        TRANSITIONS.put("do", Arrays.asList("eiusmod", "tempor", "incididunt", "ut", "labore"));
        TRANSITIONS.put("eiusmod", Arrays.asList("tempor", "incididunt", "ut", "labore", "et"));
        TRANSITIONS.put("tempor", Arrays.asList("incididunt", "ut", "labore", "et", "magna"));
        TRANSITIONS.put("incididunt", Arrays.asList("ut", "labore", "et", "magna", "aliqua"));
        TRANSITIONS.put("ut", Arrays.asList("labore", "et", "magna", "aliqua", "enim"));
        TRANSITIONS.put("labore", Arrays.asList("et", "magna", "aliqua", "enim", "ad"));
        TRANSITIONS.put("et", Arrays.asList("magna", "aliqua", "enim", "ad", "minim"));
        TRANSITIONS.put("magna", Arrays.asList("aliqua", "enim", "ad", "minim", "veniam"));
        TRANSITIONS.put("aliqua", Arrays.asList("enim", "ad", "minim", "veniam", "quis"));
        TRANSITIONS.put("enim", Arrays.asList("ad", "minim", "veniam", "quis", "nostrud"));
        TRANSITIONS.put("ad", Arrays.asList("minim", "veniam", "quis", "nostrud", "exercitation"));
        TRANSITIONS.put("exercitation", Arrays.asList("quis", "nostrud", "laboris", "ad"));
        TRANSITIONS.put("ullamco", Arrays.asList("laboris", "nisi", "aliquip", "ex", "ea"));
        TRANSITIONS.put("laboris", Arrays.asList("ex", "ea"));
        TRANSITIONS.put("nisi", Arrays.asList("aliquip", "ex", "ea", "commodo"));
        TRANSITIONS.put("aliquip", Arrays.asList("ex", "ea", "commodo", "consequat"));
        TRANSITIONS.put("ex", Arrays.asList("commodo", "consequat", "duis"));
        TRANSITIONS.put("commodo", Arrays.asList("ex", "ea", "commodo", "consequat"));
        TRANSITIONS.put("consequat", Arrays.asList("commodo", "labore", "et", "magna"));
        TRANSITIONS.put("duis", Arrays.asList("commodo", "consequat"));
        TRANSITIONS.put("aute", Arrays.asList("irure", "voluptatem", "quia"));
        TRANSITIONS.put("irure", Arrays.asList("voluptatem", "quia", "sunt"));
        TRANSITIONS.put("voluptatem", Arrays.asList("quia", "sunt", "in"));
        TRANSITIONS.put("quia", Arrays.asList("sunt", "in", "culpa"));
        TRANSITIONS.put("sunt", Arrays.asList("in", "culpa", "qui", "officia"));

        // Default transitions for other words
        for (String word : WORDS) {
            if (!TRANSITIONS.containsKey(word)) {
                TRANSITIONS.put(word, WORDS); // Fallback to any word
            }
        }
    }

    /**
     * Generates random text resembling Latin (like lorem ipsum) using a word-based Markov chain.
     *
     * @param min Minimum length of the generated text (in characters)
     * @param max Maximum length of the generated text (in characters)
     * @return A string of random Latin-like text
     */
    public static String fakeText(int min, int max) {
        if (min < 10 || max < min) {
            throw new IllegalArgumentException("Invalid range: min must be >= 10 and max must be >= min");
        }

        // Determine target length
        int targetLength = min + RANDOM.nextInt(max - min + 1);
        StringBuilder text = new StringBuilder();

        // Start with a random word
        while (text.length() < targetLength) {

            String currentWord = WORDS.get(RANDOM.nextInt(WORDS.size()));
            text.append(Character.toUpperCase(currentWord.charAt(0))).append(currentWord.substring(1));

            int lastDot = 0;
            // Generate text using word-based Markov chain
            while (text.length() < targetLength) {
                text.append(" "); // Add space between words
                currentWord = nextWord(currentWord);
                text.append(currentWord);

                // Occasionally add a period to mimic sentence structure
                if (RANDOM.nextDouble() < 0.15) {
                    text.append(". ");
                    lastDot = text.length();
                    // Capitalize next word
                    currentWord = WORDS.get(RANDOM.nextInt(WORDS.size()));
                    text.append(Character.toUpperCase(currentWord.charAt(0))).append(currentWord.substring(1));
                }
            }

            // Trim to target length
            text.setLength(lastDot);
        }
        return text.toString();
    }

    /**
     * Returns the next word based on the current word and transition probabilities.
     */
    private static String nextWord(String currentWord) {
        List<String> nextWords = TRANSITIONS.getOrDefault(currentWord, WORDS);
        return nextWords.get(RANDOM.nextInt(nextWords.size()));
    }

    public static void main(String[] args) {
        // Example usage
        for (int i = 0; i < 1000; i++) {
            String randomText = fakeText(10, 100);
            System.out.println(randomText);
        }
    }
}
