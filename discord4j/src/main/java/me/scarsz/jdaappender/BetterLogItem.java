package me.scarsz.jdaappender;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class BetterLogItem extends LogItem {
    public BetterLogItem(IChannelLoggingHandler handler, String logger, LogLevel level, String message) {
        super(handler, logger, level, message);
    }

    public BetterLogItem(LogItem old) {
        this(old.getHandler(), old.getLogger(), old.getLevel(), old.getMessage());
    }

    @Override
    protected String format(@NotNull HandlerConfig config) {
        StringBuilder builder = new StringBuilder();

        if (config.getPrefixer() != null) builder.append(config.getPrefixer().apply(this));
        if (getMessage() != null) builder.append(getHandler().escapeMarkdown(getMessage()));
        if (config.getSuffixer() != null) builder.append(config.getSuffixer().apply(this));
        if (getThrowable() != null) {
            try (StringWriter stringWriter = new StringWriter(); PrintWriter printWriter = new PrintWriter(stringWriter)) {
                getThrowable().printStackTrace(printWriter);
                builder.append('\n');
                builder.append(stringWriter);
            } catch (IOException ignored) {
            } // not possible
        }
        return builder.toString();
    }
}
