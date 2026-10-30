package se.grynna.pager.utils;

import lombok.extern.slf4j.Slf4j;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class DomainGuesstimator {

    public static String guessSplunkHost() {

        // for each domain, see if there is a matching splunk.<domain> entry
        // if so, return splunk.<domain>
        // if not, localhost
        return guessDomains().stream().map(domain -> "splunk." + domain).filter(splunkHost -> {
            try {
                Record[] records = new Lookup(splunkHost, Type.A).run();
                return records != null && records.length > 0;
            } catch (TextParseException e) {
                log.error("Error while looking up domain: {}", splunkHost, e);
                return false;
            }
        }).findAny().orElse("localhost");
    }

    public static List<String> guessDomains() {
        try {
            var result = new TreeSet<String>();

            // Get local hostname
            String hostname = InetAddress.getLocalHost().getCanonicalHostName();
            String domain = extractDomain(hostname);
            if (domain != null) {
                result.add(domain);
                log.debug("Base domain from hostname: {}", domain);
            }

            // On Windows, also try to get DNS suffixes from network interfaces
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
                List<String> windowsDomains = getWindowsDnsSuffixes();
                result.addAll(windowsDomains);
                log.debug("Windows DNS suffixes: {}", windowsDomains);
            }

            // If we still don't have any domains, warn and return empty
            if (result.isEmpty()) {
                log.warn("Could not determine any domains");
                return Collections.emptyList();
            }

            // For each domain we found, try to query SOA and NS records to find additional domains
            Set<String> domainsToQuery = new HashSet<>(result);
            for (String queryDomain : domainsToQuery) {
                // Query SOA record
                Record[] records = new Lookup(queryDomain, Type.SOA).run();
                if (records != null && records.length > 0) {
                    SOARecord soa = (SOARecord) records[0];
                    var soaDomain = soa.getName().toString();
                    if (soaDomain.endsWith(".")) {
                        soaDomain = soaDomain.substring(0, soaDomain.length() - 1);
                    }
                    log.debug("SOA Domain: {}", soaDomain);
                    result.add(soaDomain);
                }

                // Query NS records
                records = new Lookup(queryDomain, Type.NS).run();
                if (records != null) {
                    for (Record rec : records) {
                        NSRecord ns = (NSRecord) rec;
                        String nsTarget = ns.getTarget().toString();
                        if (nsTarget.endsWith(".")) {
                            nsTarget = nsTarget.substring(0, nsTarget.length() - 1);
                        }
                        log.debug("NS: {}", nsTarget);
                        // Extract domain from NS target
                        String nsDomain = extractDomain(nsTarget);
                        if (nsDomain != null) {
                            log.debug("Potential internal domain: {}", nsDomain);
                            result.add(nsDomain);
                        }
                    }
                }
            }

            return List.copyOf(result);
        } catch (Exception e) {
            log.error("Error while guessing domains", e);
            return List.of();
        }
    }

    /**
     * Get DNS suffixes from Windows network interfaces using ipconfig command
     */
    private static List<String> getWindowsDnsSuffixes() {
        List<String> domains = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("ipconfig", "/all");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), nativeProcessCharset()))) {
                String line;
                Pattern dnsSuffixPattern = Pattern.compile(".*DNS Suffix.*?:\\s*(.+)\\s*", Pattern.CASE_INSENSITIVE);

                while ((line = reader.readLine()) != null) {
                    Matcher matcher = dnsSuffixPattern.matcher(line);
                    if (matcher.matches()) {
                        String suffix = matcher.group(1).trim();
                        if (!suffix.isEmpty() && !suffix.equals("lan") && !suffix.equals("local")) {
                            domains.add(suffix);
                            log.debug("Found Windows DNS suffix: {}", suffix);
                        }
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            log.debug("Could not get Windows DNS suffixes: {}", e.getMessage());
        }

        return domains;
    }

    /**
     * Charset used by native child processes (such as {@code ipconfig}). Process output uses the
     * platform's native/console encoding, which need not be UTF-8; JDK 25 exposes it as the
     * {@code native.encoding} system property, falling back to the platform default charset.
     */
    static Charset nativeProcessCharset() {
        Charset fallback = Charset.defaultCharset();
        return Charset.forName(System.getProperty("native.encoding", fallback.name()), fallback);
    }

    static String extractDomain(String hostname) {
        if (hostname == null || hostname.isEmpty()) return null;
        // Limit 0 drops the empty label produced by a trailing dot (the FQDN root label),
        // so "www.example.com." has the same labels as "www.example.com".
        String[] parts = hostname.split("\\.", 0);
        if (parts.length > 2) {
            return String.join(".", Arrays.copyOfRange(parts, parts.length - 2, parts.length));
        }
        return hostname;
    }
}
