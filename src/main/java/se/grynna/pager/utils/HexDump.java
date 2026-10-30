package se.grynna.pager.utils;

import java.io.PrintWriter;
import java.io.StringWriter;

public class HexDump {
    public static String hexDump(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        var result = new StringWriter();
        PrintWriter out = new PrintWriter(result);

        // Convert string to UTF-8 bytes
        byte[] bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int bytesPerLine = 32;

        for (int i = 0; i < bytes.length; i += bytesPerLine) {
            // Print offset
            out.printf("%08x  ", i);

            // Print hex values
            for (int j = 0; j < bytesPerLine; j++) {
                if (i + j < bytes.length) {
                    out.printf("%02x ", bytes[i + j] & 0xFF);
                } else {
                    out.print("   "); // Pad with spaces if beyond input
                }
                // Add extra space after 16 bytes for readability
                if (j == 15) {
                    out.print(" ");
                }
            }

            // Print ASCII representation
            out.print(" |");
            for (int j = 0; j < bytesPerLine && i + j < bytes.length; j++) {
                if (j % 16 == 0 && j != 0) {
                    out.print("|");
                }
                byte b = bytes[i + j];
                // Printable ASCII range: 32 to 126
                if (b >= 32 && b <= 126) {
                    out.print((char) b);
                } else {
                    out.print(".");
                }
            }
            out.println("|");
        }

        out.flush();
        out.close();
        return result.toString();
    }

    // Example usage
    public static void main(String[] args) {
        String test = "Hello, World!\nThis is a test string for hex dump output.";
        System.out.println(hexDump(test));
    }
}
