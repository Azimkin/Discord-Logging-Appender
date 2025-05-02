package me.scarsz.jdaappender;

import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import lombok.Getter;
import lombok.SneakyThrows;
import me.scarsz.jdaappender.adapter.JavaLoggingAdapter;
import me.scarsz.jdaappender.adapter.SystemLoggingAdapter;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;

import java.io.Flushable;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ChannelLoggingHandler implements IChannelLoggingHandler, Flushable {

    @Getter
    private ScheduledExecutorService executor;
    @Getter
    private ScheduledFuture<?> scheduledFuture;

    /**
     * Schedule the handler to asynchronously flush to the logging channel every second.
     * @return this channel logging handler
     */
    public ChannelLoggingHandler schedule() {
        return schedule(1500, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule the handler to asynchronously flush to the logging channel every {period} {unit}.
     * Default is every second.
     * @param period amount of the given unit between flushes
     * @param unit the unit that the given amount is expressed in
     * @return this channel logging handler
     */
    public ChannelLoggingHandler schedule(long period, @NotNull TimeUnit unit) {
        shutdownExecutor(); // Stop the existing executor, if one exists
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
        if (scheduledFuture == null) {
            scheduledFuture = executor.scheduleAtFixedRate(() -> {
                try {
                    this.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, period, period, unit);
        }
        return this;
    }

    /**
     * Shutdown the internal executor, if active.
     * @see #schedule()
     * @see #schedule(long, TimeUnit)
     */
    public void shutdownExecutor() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignore, it's fine if the last tidbit of console output doesn't get sent
            }
            executor = null;
        }
    }


    private final Queue<BetterLogItem> queue = new ConcurrentLinkedQueue<>();
    private final HandlerConfig config = new HandlerConfig();
    private final Mono<GuildMessageChannel> channelMono;
    private final Set<Runnable> detachRunnables = new HashSet<>();

    private GuildMessageChannel channel = null;
    private Message lastMessage = null;

    public ChannelLoggingHandler(Mono<GuildMessageChannel> channelMono) {
        this.channelMono = channelMono;
    }

    public ChannelLoggingHandler(Mono<GuildMessageChannel> channelMono, Consumer<HandlerConfig> configConsumer) {
        this.channelMono = channelMono;
        configConsumer.accept(config);
    }

    @Override
    public void enqueue(LogItem logItem) {
        queue.add(new BetterLogItem(logItem));
    }

    @Override
    public void flush() {
        if (!isReady()) return; //just skip before ready //reimplemented with block just bc
        LogItem item;
        synchronized (queue) {
            StringBuilder contentToLog = new StringBuilder();
            while ((item = queue.poll()) != null) {
                contentToLog.append(item.format(config)).append("\n");
            }
            while (contentToLog.length() > 0) {
                contentToLog = appendOrSend(contentToLog);
            }
        }
    }

    private StringBuilder appendOrSend(StringBuilder content) {
        StringBuilder msgToSend = new StringBuilder();
        if (lastMessage != null) {
            msgToSend.append(escapeMarkdown(lastMessage.getContent()));
        }
        boolean isFull = false;
        StringBuilder toReturn = new StringBuilder();
        for (String str : content.toString().split("\n")) {
            if (msgToSend.length() + str.length() + 12 < Message.MAX_CONTENT_LENGTH) {
                msgToSend.append(str).append("\n");
            } else {
                isFull = true;
                toReturn.append(str).append('\n');
                break;
            }
        }
        if (lastMessage != null) {
            lastMessage = lastMessage.edit().withContentOrNull(formatBeforeSending(msgToSend.toString())).block();
        } else {
            lastMessage = channel.createMessage(formatBeforeSending(msgToSend.toString())).block();
        }
        if (isFull) {
            lastMessage = null;
        }
        return toReturn;
    }

    private String formatBeforeSending(String item) {
        return "```ansi\n" + item + "```";
    }

    public boolean isReady() {
        if (channel == null) {
            channel = channelMono.block();
        }
        return true;
    }

    /**
     * Shuts down the internal executor, and detaches attached loggers.
     * @see #shutdownExecutor()
     * @see #detach()
     */
    public void shutdown() {
        detach();
        shutdownExecutor();
    }

    public ChannelLoggingHandler attach() {
        // log4j?
        try {
            Class.forName("org.apache.logging.log4j.core.Logger");
            return attachLog4jLogging();
        } catch (Throwable ignored) {
        }

        // logback?
        try {
            Class.forName("ch.qos.logback.core.Appender");
            return attachLogbackLogging();
        } catch (Throwable ignored) {
        }

        // slf4j?
        try {
            Class<?> logFactoryClass = Class.forName(org.slf4j.impl.StaticLoggerBinder.getSingleton().getLoggerFactoryClassStr());
            switch (logFactoryClass.getSimpleName()) {
                case "JDK14LoggerFactory":
                    return attachJavaLogging();
                case "ContextSelectorStaticBinder":
                    return attachLogbackLogging();
                //TODO more SLF4J implementations
                default:
                    System.err.println("SLF4J Logger factory " + logFactoryClass.getName() + " is not supported");
                    enqueue(new LogItem(this, "Appender", LogLevel.ERROR, "SLF4J Logger factory " + logFactoryClass.getName() + " is not supported"));
            }
        } catch (Throwable ignored) {
        }

        return attachSystemLogging();
    }

    public void detach() {
        Iterator<Runnable> iterator = detachRunnables.iterator();
        while (iterator.hasNext()) {
            Runnable runnable = iterator.next();
            runnable.run();
            iterator.remove();
        }
    }

    public ChannelLoggingHandler attachSystemLogging() {
        SystemLoggingAdapter adapter = new SystemLoggingAdapter(this);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(adapter.getOutStream());
        System.setErr(adapter.getErrStream());
        detachRunnables.add(() -> {
            System.setOut(originalOut);
            System.setErr(originalErr);
        });
        return this;
    }

    public ChannelLoggingHandler attachJavaLogging() {
        JavaLoggingAdapter adapter = new JavaLoggingAdapter(this);
        java.util.logging.Logger.getLogger("").addHandler(adapter);
        detachRunnables.add(() -> java.util.logging.Logger.getLogger("").removeHandler(adapter));
        return this;
    }

    @SneakyThrows
    public ChannelLoggingHandler attachLog4jLogging() {
        org.apache.logging.log4j.Logger rootLogger = org.apache.logging.log4j.LogManager.getRootLogger();
        Method addAppenderMethod = rootLogger.getClass().getMethod("addAppender", org.apache.logging.log4j.core.Appender.class);
        Method removeAppenderMethod = rootLogger.getClass().getMethod("removeAppender", org.apache.logging.log4j.core.Appender.class);

        Object adapter = Class.forName("me.scarsz.jdaappender.adapter.Log4JLoggingAdapter")
                .getConstructor(IChannelLoggingHandler.class)
                .newInstance(this);
        addAppenderMethod.invoke(rootLogger, adapter);

        detachRunnables.add(() -> {
            try {
                removeAppenderMethod.invoke(rootLogger, adapter);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return this;
    }

    @SneakyThrows
    public ChannelLoggingHandler attachLogbackLogging() {
        ch.qos.logback.classic.LoggerContext loggerContext = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        org.slf4j.Logger rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        Method addAppenderMethod = rootLogger.getClass().getMethod("addAppender", ch.qos.logback.core.Appender.class);
        Method detachAppenderMethod = rootLogger.getClass().getMethod("detachAppender", ch.qos.logback.core.Appender.class);

        Object adapter = Class.forName("me.scarsz.jdaappender.adapter.LogbackLoggingAdapter")
                .getConstructor(ChannelLoggingHandler.class, ch.qos.logback.classic.LoggerContext.class)
                .newInstance(this, loggerContext);
        addAppenderMethod.invoke(rootLogger, adapter);

        detachRunnables.add(() -> {
            try {
                detachAppenderMethod.invoke(rootLogger, adapter);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return this;
    }

    @Override
    public String escapeMarkdown(String message) {
        return new JdaMarkdownSanitizer().withIgnored(0).compute(message);
    }
}
