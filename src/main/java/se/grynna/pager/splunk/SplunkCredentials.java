package se.grynna.pager.splunk;

/**
 * Splunk connection credentials.
 * Collected by UserLoginPanel, returned by LoginProvider, used by SearchManager.
 */
public record SplunkCredentials(String host, int port, String username, String password) {}
