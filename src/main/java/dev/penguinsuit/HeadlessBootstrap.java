package dev.penguinsuit;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Select server-side AWT before ImageIO or another mod initializes AWT classes. */
public final class HeadlessBootstrap implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        configure();
    }

    public static void configure() {
        // Respect an administrator's explicit JVM choice. A late property change
        // cannot repair a native class whose static initializer has already failed.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        } else if (!Boolean.parseBoolean(System.getProperty("java.awt.headless"))) {
            System.err.println("[Penguin suit] java.awt.headless=false: image decoding may require desktop libraries. "
                    + "On a server without them, restart Java with -Djava.awt.headless=true before -jar.");
        }
    }
}
