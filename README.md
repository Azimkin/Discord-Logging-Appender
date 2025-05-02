# Discord Logging Appender
Library for Java applications that use [Discord4j](https://discord4j.com/) or [JDA](https://github.com/DV8FromTheWorld/JDA) to easily stream
the logging output of the application to a text channel on Discord.

[insert screenshot here]

# Usage
Using default values:

```java
ChannelLoggingHandler handler = new ChannelLoggingHandler(gatewayClient.getChannelById(Snowflake.of("your channel id")).typeOf(GuildMessageChannel.class))
        .attach() // attach to SLF4J JDK logging if present, else Log4j if present, else standard out/err
        .schedule(); // schedule handler to flush output asynchronously every 1.5 seconds
```

Customizing config values, such as adding a logger name mapping:

```java
ChannelLoggingHandler handler = new ChannelLoggingHandler(gatewayClient.getChannelById(Snowflake.of("your channel id")).typeOf(GuildMessageChannel.class), config -> {
    config.setColored(true); // enable coloring of different log levels, default true
    config.setSplitCodeBlockForLinks(false); // split the output code blocks when a link is present, default false
    config.setAllowLinkEmbeds(true); // when splitting code blocks for links, allow the links to have an embed, default true
    config.mapLoggerName("discord4j", "Discord"); // add a mapping for logger names "net.dv8tion.jda*" to just be "JDA"
}).attach().schedule();
```

# Artifact
```xml
<repository>
    <id>azimkin-repo-releases</id>
    <url>https://repo.azimkin.top/releases</url>
</repository>
```
```xml
<!-- discord4j -->
<dependency>
    <groupId>me.scarsz.jdaappender</groupId>
    <artifactId>discord4j</artifactId>
    <version>1.2.4</version>
</dependency>
```

# Using with SLF4J-backed apps with minimal effort (JDA, ...)

Use `slf4j-jdk14` as your SLF4J logging backend. This will forward SLF4J messages to java.util.logging and can be
picked up by JDAAppender with `ChannelLoggingHandler#attachJavaLogging`.

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-jdk14</artifactId>
    <version>1.7.31</version>
</dependency>
```

Author: [Scarsz](https://github.com/Scarsz/)
Original library: [JDAAppender](https://github.com/Scarsz/JDAAppender)