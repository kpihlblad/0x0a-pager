package se.grynna.pager.splunk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splunk.Event;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.Serializable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.Double.min;
import static org.apache.commons.lang3.StringUtils.containsWhitespace;

@Slf4j
@Getter
public class FieldSummary implements Comparable<FieldSummary>, java.io.Serializable {
    private String field;
    private final double relevance;

    Map<String, Value> values;

    public record Value(String value, long count) implements Serializable {}

    private static final ObjectMapper mapper = new ObjectMapper();

    public FieldSummary(Event event) {
        field = event.get("field");
        var count = parseLong(event.get("count"));
        var distinctCount = parseLong(event.get("distinct_count"));

        //        var numericCount = parseLong(event.get("numeric_count"));
        //        var min = parseDouble(event.get("min"));
        //        var max = parseDouble(event.get("max"));
        //        var mean = parseDouble(event.get("mean"));
        //        var stdev = parseDouble(event.get("stdev"));
        this.values = parseValues(event.get("values"));
        // Event counts are far below 2^53 (the exact integer range of double), so the
        // long-to-double conversion is an intentional, effectively lossless approximation.
        this.relevance = calculateFilteringRelevance((double) distinctCount, (double) count);
    }

    private FieldSummary(String field, Map<String, Value> values, double relevance) {
        this.field = field;
        this.values = values;
        this.relevance = relevance;
    }

    private Map<String, Value> parseValues(String valueJson) {
        // log.debug("parseValues: {}", valueJson);
        // valueJson is a JSON string that can be a single value or an array of values
        if (valueJson == null || valueJson.isEmpty()) {
            return Map.of();
        }
        // If the valueJson is a single value, we need to wrap it in an array
        // to ensure consistent processing
        // This is a workaround for the fact that Splunk sometimes returns a single value as a string
        // and sometimes as an array of strings.
        if (!valueJson.startsWith("[") && !valueJson.startsWith("{")) {
            valueJson = "[" + valueJson + "]";
        }
        // parse the JSON string into a list of Value records

        try {
            var valueList = Arrays.asList(mapper.readValue(valueJson, Value[].class));

            return valueList.stream()
                    .collect(Collectors.toMap(Value::value, Function.identity(), (a, b) -> a, TreeMap::new));

        } catch (JsonProcessingException e) {
            log.error("Failed to parse values from JSON: {}", valueJson, e);
            return Map.of();
        }
    }

    @Override
    public int compareTo(FieldSummary o) {
        return Strings.CS.compare(this.field, o.field);
    }

    private long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return Long.parseLong(value);
    }

    /**
     * Calculates a heuristic score for how relevant this field is for filtering.
     * Higher scores indicate more useful fields for filtering.
     *
     * DATA-DRIVEN ALGORITHM (NO field name analysis):
     * 1. CARDINALITY: Check if field has useful number of distinct values
     * 2. PATTERN DETECTION: Reject pure random patterns (UUIDs, hashes)
     * 3. DISTRIBUTION: Prefer fields with meaningful value distribution
     * 4. VALUE STRUCTURE: Check if values are structured/meaningful vs random
     *
     * Key insight: Field relevance is determined purely by data characteristics,
     * not by field names. This makes the algorithm objective and portable.
     *
     * @return relevance score between 0.0 (completely irrelevant) and 1.0 (highly relevant)
     * @param distinctCount number of distinct values
     * @param count total number of occurrences
     */
    private double calculateFilteringRelevance(double distinctCount, double count) {
        log.trace("Calculating filtering relevance for field '{}'", field);

        // Must have at least 2 distinct values to be useful for filtering
        if (values == null || values.size() < 2) {
            log.trace("Field '{}' has less than 2 distinct values, relevance=0.0", field);
            return 0.0;
        }

        // Guard the zero denominator: with no events the ratio is undefined; treat as 0.
        double cardinalityRatio = count == 0.0 ? 0.0 : distinctCount / count;

        // 1. CARDINALITY SCORE: Is the uniqueness ratio reasonable?
        double cardinalityScore = calculateCardinalityRelevance(distinctCount, cardinalityRatio);

        // 3. DISTRIBUTION SCORE: Is the value distribution useful?
        double distributionScore = calculateDistributionRelevance();

        // 4. VALUE STRUCTURE SCORE: Do values look meaningful?
        double structureScore = calculateValueStructureScore();

        // MULTIPLICATIVE SCORING with vetoes:
        // Pattern and structure act as VETOES - if values are pure random/meaningless, kill the score
        // Base score from cardinality (50%) and distribution (50%)
        double baseScore = (cardinalityScore * 0.65) + (distributionScore * 0.35);

        // Apply structure veto (multiplicative)
        // If structure=0 (no meaningful structure), reduce score
        double structureMultiplier = logistic(structureScore, 1.031, 8.134, 0.5);

        // Apply uniform distribution veto (infrastructure artifact detection)
        // If values are uniformly distributed with very low cardinality ratio,
        // it's likely infrastructure noise (load-balanced servers, round-robin assignments)
        double uniformityMultiplier = 1.0;
        if (cardinalityRatio < 0.001 && isUniformDistribution()) {
            uniformityMultiplier = 0.4; // Heavy veto for uniform infrastructure fields
        }

        // Apply extreme dominance veto (error message/rare value detection)
        // If field has 2-3 values with one dominating >80%, it's likely error messages or edge cases
        double dominanceMultiplier = 1.0;
        if (distinctCount <= 3 && hasExtremeDominance()) {
            dominanceMultiplier = 0.3; // Heavy veto for error message fields
        }

        // Combine: base score reduced by pattern, structure, uniformity, and dominance vetoes
        double relevanceScore = baseScore * structureMultiplier * uniformityMultiplier * dominanceMultiplier;

        var result = Math.max(0.0, Math.min(1.0, relevanceScore));

        log.debug("Field '{}': cardinality={}, distribution={}, structure={}, "
                + "base={}, structureMult={}, uniformMult={}, domMult={}, distinct={}, ratio={} -> relevance={}", field,
                cardinalityScore, distributionScore, structureScore, baseScore, structureMultiplier,
                uniformityMultiplier, dominanceMultiplier, distinctCount, cardinalityRatio, result);

        return result;
    }

    private double logistic(double x, double max, double growthRate, double midpoint) {
        // Logistic function formula
        return min(1.0, max / (1 + Math.exp(-growthRate * (x - midpoint))));
    }

    /**
     * Calculate cardinality-based relevance score.
     *
     * OPTIMAL RANGES (adjusted based on practical experience):
     * - 2-3 distinct with extreme skew: 0.5 (likely error messages/rare values)
     * - 2-5 distinct with balanced: 0.9 (small categorical - e.g., log levels)
     * - 6-20 distinct: 1.0 (optimal categorical - perfect for filtering)
     * - 21-50 distinct: 0.8 (good granularity)
     * - 51-100 distinct: 0.6 (moderate, getting high)
     * - >100 distinct: 0.3-0.4 (high cardinality, less useful per user feedback)
     *
     * CARDINALITY RATIO with distribution context:
     * - <0.001% unique (very low ratio): Check distribution uniformity
     *   → If uniform (load-balanced servers): penalize (0.5x)
     *   → If skewed (real categorical): boost (0.95x)
     * - 0.001-1% unique: 0.9 (categorical)
     * - 1-50% unique: 1.0 (optimal distribution)
     * - 50-95% unique: 0.6 (getting too unique)
     * - >95% unique: 0.2 (essentially unique IDs)
     */
    private double calculateCardinalityRelevance(double distinctCount, double cardinalityRatio) {
        double score;

        // Score based on absolute distinct count (lowered thresholds)
        if (distinctCount <= 1) {
            score = 0;
            // Check for extreme skew in very low cardinality (2-3 values)
            // This catches error message fields and rare technical values

        } else if (distinctCount <= 3 && hasExtremeDominance()) {
            score = 0.5; // Penalize fields with one dominant value out of 2-3 total
        } else if (distinctCount <= 25) {
            score = 1.0; // Sweet spot for categorical filtering
        } else if (distinctCount <= 50) {
            score = 0.75; // Sweet spot for categorical filtering
        } else if (distinctCount <= 60) {
            score = 0.5; // Sweet spot for categorical filtering
        } else if (distinctCount <= 70) {
            score = 0.45; // Sweet spot for categorical filtering
        } else if (distinctCount <= 80) {
            score = 0.4; // Good but getting higher
        } else if (distinctCount <= 100) {
            score = 0.3; // Moderate - less useful per user feedback
        } else if (distinctCount <= 250) {
            score = 0.2; // Moderate - less useful per user feedback
        } else if (distinctCount <= 450) {
            score = 0.1; // Moderate - less useful per user feedback
        } else {
            score = 0.0; // High cardinality - basically less useful
        }

        // Adjust based on cardinality ratio with distribution awareness
        if (cardinalityRatio > 0.95) {
            score *= 0.2; // Almost all unique - essentially IDs
        } else if (cardinalityRatio > 0.5) {
            score *= 0.7; // More than half unique - getting noisy
        } else if (cardinalityRatio < 0.001) {
            // Very low ratio: distinguish uniform (infrastructure) vs skewed (categorical)
            if (isUniformDistribution()) {
                // Uniform distribution with low ratio = infrastructure noise (load-balanced servers)
                score *= 0.2; // Very heavy penalty for uniform distribution (targets below 0.3 threshold)
            } else {
                // Skewed distribution with low ratio = valuable categorical field
                score *= 0.95;
            }
        } else if (cardinalityRatio < 0.01) {
            score *= 0.9; // Low ratio, likely categorical
        }

        return score;
    }

    /**
     * Check if value distribution is essentially uniform (all counts similar).
     * Uniform distribution often indicates infrastructure artifacts (load balancing, round-robin).
     */
    private boolean isUniformDistribution() {
        if (values.isEmpty()) return false;

        long[] counts = values.values().stream().mapToLong(Value::count).sorted().toArray();

        if (counts.length < 2) return false;

        // Check if min and max counts are within 2x of each other (coefficient of variation)
        long min = counts[0];
        long max = counts[counts.length - 1];

        // If max is less than 2x the min, distribution is essentially uniform
        return max < min * 2;
    }

    /**
     * Check if one value extremely dominates the distribution (>80% of occurrences)
     * AND the field appears to be error messages rather than useful categorical values.
     *
     * Combined with very low cardinality (2-3 values), extreme dominance can indicate:
     * - Error messages (one common error, few rare errors) → FILTER OUT
     * - Technical values (one dominant state, few edge cases) → FILTER OUT
     * - BUT NOT: Useful categorical fields (EVENT/ALARM/DEBUG) → KEEP
     *
     * Key distinction: Look at the VALUES themselves to distinguish:
     * - Categorical: Short, simple, uppercase/word-like names (EVENT, ALARM, ERROR, INFO)
     * - Error messages: Long, complex, specific technical strings with punctuation/packages
     */
    private boolean hasExtremeDominance() {
        if (values.isEmpty()) return false;

        long totalCount = values.values().stream().mapToLong(Value::count).sum();
        if (totalCount == 0) return false; // No occurrences -> nothing can dominate
        long maxCount = values.values().stream().mapToLong(Value::count).max().orElse(0);

        // Check if the most common value accounts for >80% of all occurrences
        double dominanceRatio = (double) maxCount / totalCount;

        if (dominanceRatio <= 0.8) {
            return false; // Not extremely dominant
        }

        // Extreme dominance detected - now check if values look like categorical vs error messages
        // If ALL values look like simple categorical names, keep the field
        boolean allValuesCategorical = values.values().stream().allMatch(v -> isCategoricalValue(v.value()));

        if (allValuesCategorical) {
            // All values are simple categorical names (like EVENT, ALARM, DEBUG)
            // Even with extreme skew, these are useful for filtering
            log.trace("Field '{}': Extreme dominance but all values are categorical, keeping field", field);
            return false; // Don't apply dominance penalty
        }

        // Some values look like error messages or technical strings
        log.trace("Field '{}': Extreme dominance with non-categorical values, applying penalty", field);
        return true; // Apply dominance penalty
    }

    /**
     * Check if a value looks like a categorical identifier (useful for filtering)
     * vs a log message or random pattern.
     *
     * CATEGORICAL values (useful for filtering):
     * - Package names: com.example.gpi.stream.Streams ✓
     * - Pod names: css-import-655c99bf59-hxc4k ✓
     * - Service names: cas-public-service-api ✓
     * - Simple categories: EVENT, ALARM, DEBUG ✓
     * - NO SPACES (key indicator!)
     *
     * NON-CATEGORICAL (filter out):
     * - Contains spaces → log messages, error descriptions
     * - Random patterns → UUIDs, long hex strings, long numeric IDs
     * - Examples: "(1) checkLaggingMessages failed", "00D26B7A344033E26400060808"
     */
    private boolean isCategoricalValue(String value) {
        if (value == null || value.trim().isEmpty()) return false;

        String trimmed = value.trim();

        // PRIMARY INDICATOR: Spaces = log message/description, NOT categorical
        if (trimmed.contains(" ")) {
            return false; // Contains spaces = sentence/description/log message
        }

        // Check for random patterns (UUIDs) - even without spaces, these aren't categorical
        if (isUuidPattern(trimmed)) {
            return false; // UUIDs are random, not categorical
        }

        // Check for hex strings (hashes/random IDs) - not categorical
        // Lowered threshold from 32 to 20 to catch values like "00D26B7A344033E26400060808"
        if (trimmed.matches("^[a-fA-F0-9]{20,}$")) {
            return false; // Long hex string = hash/random ID
        }

        // Check for very long numeric IDs - not categorical
        if (trimmed.matches("^[0-9]{15,}$")) {
            return false; // Very long numeric ID = random/timestamp
        }

        // Everything else WITHOUT spaces is considered categorical:
        // - Package names: com.example.gpi.stream.Streams
        // - Pod names: css-import-655c99bf59-hxc4k (has letters mixed with shorter hex)
        // - Service names: cas-public-service-api
        // - Categories: EVENT, ALARM, DEBUG
        // - Hostnames: lsp86370a.prd.example.net
        return true;
    }

    /**
     * Calculate distribution-based relevance score.
     *
     * Good filtering fields have some structure in their distribution:
     * - Not all singletons (that's just noise)
     * - Not completely uniform (less useful for filtering)
     * - Some values more common than others (indicates natural groupings)
     */
    private double calculateDistributionRelevance() {
        if (values.isEmpty()) return 0.0;

        // Check singleton ratio
        long singletonCount = values.values().stream().filter(v -> v.count() == 1).count();
        double singletonRatio = (double) singletonCount / values.size();

        // All or mostly singletons = noise
        if (singletonRatio > 0.9) return 0.1;
        if (singletonRatio > 0.7) return 0.4;

        // Check if there's any variance in counts (Gini coefficient)
        double gini = calculateGiniCoefficient(values.values());

        // Perfect equality (gini=0) is less useful, moderate inequality is good
        if (gini < 0.1) return 0.6; // Too uniform
        if (gini > 0.9) return 0.7; // Very skewed but might be categorical

        return 1.0; // Good distribution
    }

    /**
     * Calculate value structure score - are values meaningful or random?
     *
     * Checks what percentage of values appear to be structured/meaningful
     * vs random patterns.
     */
    private double calculateValueStructureScore() {
        if (values.isEmpty()) return 0.5;

        long meaningfulOccurrences = 0;
        long totalOccurrences = 0;
        int meaningfulCount = 0;
        int totalCount = 0;

        for (Value value : values.values()) {
            String val = value.value();

            totalCount++;
            totalOccurrences += value.count();

            // Empty values are not meaningful for filtering
            if (val == null || val.isEmpty()) {
                continue; // Don't increment meaningfulCount/meaningfulOccurrences
            }

            // Check if value appears meaningful: whitespace (likely log messages), pure
            // UUIDs, long hex strings and long numeric IDs are not meaningful for
            // filtering; anything else (structured names, words, short numbers) is.
            boolean isMeaningful = !containsWhitespace(val) && !isUuidPattern(val) && !val.matches("^[a-fA-F0-9]{32,}$")
                    && !val.matches("^[0-9]{10,}$");

            if (isMeaningful) {
                meaningfulCount++;
                meaningfulOccurrences += value.count();
            }
        }

        if (totalCount == 0 || totalOccurrences == 0) return 0.5;

        // Weight by occurrence count (80%) and distinct count (20%)
        double occurrenceRatio = (double) meaningfulOccurrences / totalOccurrences;
        double distinctRatio = (double) meaningfulCount / totalCount;
        double weightedStructure = (occurrenceRatio * 0.8) + (distinctRatio * 0.2);

        log.trace("Field '{}': {} meaningful out of {} distinct, {} occurrences out of {} total, weighted={}", field,
                meaningfulCount, totalCount, meaningfulOccurrences, totalOccurrences, weightedStructure);

        return weightedStructure;
    }

    /**
     * Checks if string has UUID pattern (8-4-4-4-12 hex format).
     */
    private boolean isUuidPattern(String value) {
        return value.matches("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$");
    }

    /**
     * SUB-ALGORITHM: Gini Coefficient Calculation
     *
     * Measures inequality in value count distribution.
     * Used in economics to measure wealth inequality, here applied to log value frequencies.
     *
     * FORMULA: Gini = Σ(2i - n - 1) * x_i / (n * Σx_i)
     * where x_i are sorted counts, n is number of values
     *
     * INTERPRETATION:
     * - 0.0 = Perfect equality (all values have same count)
     * - 0.5 = Moderate inequality (some values more common)
     * - 1.0 = Perfect inequality (one value has all occurrences)
     *
     * FOR FILTERING:
     * - 0.2-0.8 = Good (some structure, not completely flat or skewed)
     * - <0.2 = Too flat (all values equally common, less useful for filtering)
     * - >0.9 = Too skewed (one value dominates, filtering less effective)
     */
    private double calculateGiniCoefficient(Collection<Value> values) {
        if (values.size() <= 1) return 0.0;

        // Sort counts in ascending order for Gini calculation
        long[] counts = values.stream().mapToLong(Value::count).sorted().toArray();

        double sum = 0;
        for (int i = 0; i < counts.length; i++) {
            // Gini formula: weight each value by its position in sorted order
            sum += (2L * (i + 1) - counts.length - 1) * counts[i];
        }

        double totalCount = Arrays.stream(counts).sum();
        if (totalCount == 0) return 0.0; // All counts equal (zero) -> perfect equality, no division by zero
        return sum / (totalCount * (counts.length - 1));
    }

    /**
     * Analyzes word boundaries to see if they're meaningful.
     */
    boolean hasMeaningfulWordBoundaries(String value) {
        // Split on common separators and analyze segments. Limit 0 drops trailing empty
        // segments, so a value ending in a separator ("api-gateway-") has the same segments
        // as its stripped form, while an empty segment between separators ("api--gateway")
        // still counts against the word-like ratio below.
        String[] segments = value.split("[._-]", 0);
        if (segments.length < 2) {
            // No separators, check for natural word boundaries in CamelCase
            return value.matches(".*[a-z][A-Z].*"); // CamelCase boundaries
        }

        // Check if most segments look like words (not random)
        int wordLikeSegments = 0;
        for (String segment : segments) {
            if (segment.length() >= 2 && hasWordLikeCharacterPattern(segment)) {
                wordLikeSegments++;
            }
        }

        return wordLikeSegments >= segments.length * 0.7; // 70% of segments should be word-like
    }

    /**
     * Checks if string contains word-like segments by analyzing character patterns.
     */
    boolean hasWordLikeSegments(String value) {
        // Look for segments that have natural word characteristics: split before each
        // uppercase letter and on separators. Limit -1 keeps every field — empty segments
        // are never word-like (length < 2), so they cannot change the count below.
        String[] possibleWords = value.split("(?=[A-Z])|[._-]", -1);

        int wordLikeCount = 0;
        for (String segment : possibleWords) {
            if (segment.length() >= 2 && hasWordLikeCharacterPattern(segment)) {
                wordLikeCount++;
            }
        }

        return wordLikeCount >= 2; // At least 2 word-like segments
    }

    /**
     * Analyzes if a segment has word-like character patterns.
     */
    private boolean hasWordLikeCharacterPattern(String segment) {
        if (segment.length() < 2) return false;

        segment = segment.toLowerCase(Locale.ROOT);

        // Check for natural word patterns
        // 1. Not all same character
        if (segment.chars().distinct().count() < 2) return false;

        // 2. Contains vowels (words usually have vowels)
        if (!segment.matches(".*[aeiou].*")) return false;

        // 3. Not pure numbers
        if (segment.matches("^[0-9]+$")) return false;

        // 4. Reasonable consonant clusters (not too many consecutive consonants)
        if (segment.matches(".*[bcdfghjklmnpqrstvwxyz]{4,}.*")) return false;

        // 5. Contains at least some letters
        if (!segment.matches(".*[a-z].*")) return false;

        return true;
    }

    /**
     * Convenience method to check if this field should be filtered out based on relevance.
     *
     * @param threshold minimum relevance score to keep field (default: 0.3)
     * @return true if field should be kept for filtering
     */
    public boolean isRelevantForFiltering(double threshold) {
        return relevance >= threshold;
    }

    /**
     * Convenience method with default threshold.
     */
    public boolean isRelevantForFiltering() {
        return isRelevantForFiltering(0.3);
    }

    public FieldSummary merge(FieldSummary other) {

        // keep the max relevance
        double relevance = Math.max(this.relevance, other.relevance);

        // merge the values maps
        Map<String, Value> mergedValues = new HashMap<>(this.values);
        for (Map.Entry<String, Value> entry : other.values.entrySet()) {
            mergedValues.merge(entry.getKey(), entry.getValue(), (v1, v2) -> new Value(v2.value(), v2.count()));
        }
        return new FieldSummary(this.field, mergedValues, relevance);
    }
}
