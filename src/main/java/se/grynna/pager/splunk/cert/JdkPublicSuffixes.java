package se.grynna.pager.splunk.cert;

import java.io.IOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the public-suffix rules shipped with the running JDK. */
final class JdkPublicSuffixes {

    private static final System.Logger LOG = System.getLogger(JdkPublicSuffixes.class.getName());
    private static final Path LIST_FILE = Path.of(System.getProperty("java.home"), "lib", "security",
            "public_suffix_list.dat");
    private static final ConcurrentMap<String, Optional<List<Rule>>> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean listUnavailable;

    private JdkPublicSuffixes() {}

    /**
     * Mirrors the JDK TLS check: a wildcard is forbidden when the suffix after
     * the wildcard is itself the ICANN public suffix selected for {@code z.<suffix>}.
     */
    static boolean isIcannPublicSuffix(String suffix) {
        final String asciiSuffix;
        try {
            asciiSuffix = IDN.toASCII(suffix).toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return true; // Invalid/unknown suffixes are not safe to wildcard.
        }

        if (listUnavailable) {
            // Fail closed if the JDK's list cannot be read: never display a
            // wildcard as covering a host when its public-suffix status is unknown.
            return true;
        }

        String candidate = "z." + asciiSuffix;
        int lastDot = candidate.lastIndexOf('.');
        String topLevelDomain = lastDot < 0 ? candidate : candidate.substring(lastDot + 1);
        Optional<List<Rule>> rules = CACHE.computeIfAbsent(topLevelDomain, JdkPublicSuffixes::loadRules);
        if (rules.isEmpty()) {
            return listUnavailable;
        }

        Match match = findPublicSuffix(candidate, rules.get());
        return match != null && match.icann() && asciiSuffix.equalsIgnoreCase(match.publicSuffix());
    }

    private static Optional<List<Rule>> loadRules(String topLevelDomain) {
        try (ZipFile zip = new ZipFile(LIST_FILE.toFile())) {
            ZipEntry entry = zip.getEntry(topLevelDomain);
            if (entry == null) {
                return Optional.of(List.of());
            }
            try (var input = zip.getInputStream(entry)) {
                return Optional.of(parseRules(input.readAllBytes()));
            }
        } catch (IOException | RuntimeException e) {
            listUnavailable = true;
            LOG.log(System.Logger.Level.WARNING,
                    "Unable to read the JDK public-suffix list; wildcard hostname coverage will fail closed", e);
            return Optional.empty();
        }
    }

    /**
     * The JDK's zip entries encode each PSL rule as one authority byte followed
     * by UTF-8 rule text and a newline: 0 is ICANN, 1 is private.
     */
    private static List<Rule> parseRules(byte[] data) {
        List<Rule> rules = new ArrayList<>();
        int start = 0;
        while (start < data.length) {
            int end = start;
            while (end < data.length && data[end] != '\n') {
                end++;
            }
            if (end - start > 1) {
                boolean icann = data[start] == 0;
                int ruleEnd = end;
                if (data[ruleEnd - 1] == '\r') {
                    ruleEnd--;
                }
                String value = new String(data, start + 1, ruleEnd - start - 1, StandardCharsets.UTF_8)
                        .toLowerCase(java.util.Locale.ROOT);
                rules.add(new Rule(value, icann));
            }
            start = end + 1;
        }
        return List.copyOf(rules);
    }

    /** Selects the same prevailing rule shape as the JDK: longest suffix rule, with exceptions first. */
    private static Match findPublicSuffix(String domain, List<Rule> rules) {
        String[] labels = domain.split("\\.");
        Match best = null;
        Match exception = null;

        for (Rule rule : rules) {
            String value = rule.value();
            if (value.startsWith("!")) {
                String exceptionName = value.substring(1);
                int count = labelCount(exceptionName);
                if (count > 1 && matchesTail(labels, exceptionName, false)
                        && (exception == null || count > exception.ruleLabels())) {
                    exception = new Match(suffix(labels, count - 1), rule.icann(), count, true);
                }
                continue;
            }

            boolean wildcard = value.startsWith("*.");
            String ruleBody = wildcard ? value.substring(2) : value;
            int count = labelCount(value);
            if (count <= 0 || !matchesTail(labels, ruleBody, wildcard)) {
                continue;
            }
            if (best == null || count > best.ruleLabels()) {
                best = new Match(suffix(labels, count), rule.icann(), count, false);
            }
        }
        return exception != null ? exception : best;
    }

    /** Matches a rule to domain labels; a leading wildcard consumes one label. */
    private static boolean matchesTail(String[] domainLabels, String rule, boolean wildcard) {
        // Limit 0 drops a trailing empty label, consistent with the domain split in
        // findPublicSuffix and with labelCount, so a rule's label count agrees everywhere.
        String[] ruleLabels = rule.split("\\.", 0);
        int required = ruleLabels.length + (wildcard ? 1 : 0);
        if (domainLabels.length < required) {
            return false;
        }
        int domainOffset = domainLabels.length - ruleLabels.length;
        for (int i = 0; i < ruleLabels.length; i++) {
            String ruleLabel = ruleLabels[i];
            if (!ruleLabel.equals("*") && !ruleLabel.equalsIgnoreCase(domainLabels[domainOffset + i])) {
                return false;
            }
        }
        return !wildcard || domainOffset > 0;
    }

    private static String suffix(String[] labels, int count) {
        int start = labels.length - count;
        return String.join(".", java.util.Arrays.copyOfRange(labels, start, labels.length));
    }

    private static int labelCount(String rule) {
        return rule.split("\\.").length;
    }

    private record Rule(String value, boolean icann) {}

    private record Match(String publicSuffix, boolean icann, int ruleLabels, boolean exception) {}
}
