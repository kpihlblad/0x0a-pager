package se.grynna.pager.splunk;

public class SearchCancelledException extends RuntimeException {
    public SearchCancelledException(String s, InterruptedException e) {
        super(s, e);
    }
}
