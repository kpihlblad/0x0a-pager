package se.grynna.pager.splunk;

public sealed interface LogLine
        permits AfterBoundaryLine, BeforeBoundaryLine, LoadedLogLine, NowBoundaryLine, Placeholder {

    int line();
}
