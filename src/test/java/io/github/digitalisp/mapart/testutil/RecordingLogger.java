package io.github.digitalisp.mapart.testutil;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.List;

/** slf4j logger that keeps every formatted line as "LEVEL message". */
public final class RecordingLogger extends LegacyAbstractLogger {
    public final List<String> lines = new ArrayList<>();

    public RecordingLogger() {
        this.name = "recording";
    }

    public List<String> at(Level level) {
        return lines.stream().filter(l -> l.startsWith(level.name() + " ")).map(l -> l.substring(level.name().length() + 1))
            .toList();
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String msg, Object[] args, Throwable t) {
        lines.add(level.name() + " " + MessageFormatter.basicArrayFormat(msg, args));
    }

    @Override public boolean isTraceEnabled() { return true; }
    @Override public boolean isDebugEnabled() { return true; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isErrorEnabled() { return true; }
}
