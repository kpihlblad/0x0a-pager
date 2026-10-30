package se.grynna.pager.utils;

import org.apache.commons.io.IOUtils;

import java.nio.charset.StandardCharsets;

public class SbomTransformer {

    public static String transformSbom() {

        String sb = """
                0x0A Pager is licensed under the Apache License 2.0, with some components under different licenses.
                Copyright (c) 2025 Karl Pihlblad.

                0x0A Pager is an independent project and is not affiliated with, sponsored by or endorsed by Splunk LLC
                or Cisco Systems, Inc. Splunk, Splunk>, and Turn Data Into Doing are trademarks or registered
                trademarks of Splunk LLC in the United States and other countries. All other brand names, product
                names, or trademarks belong to their respective owners.

                The Author gratefully acknowledges the use of the following open-source components:

                """ + resource("/raven_licences.txt") + "\n" + resource("/THIRD-PARTY.txt") + "\n\n\n\n"
                + resource("/all_licences.txt") + "\n";
        return sb;
    }

    private static String resource(String resourcePath) {
        try {
            return IOUtils.toString(SbomTransformer.class.getResourceAsStream(resourcePath), StandardCharsets.UTF_8)
                    .trim();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read generated sbom resource - (If developing try mvn clean package) " + resourcePath,
                    e);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println(transformSbom());
    }
}
