package dev.lumora.core.browser;

/**
 * What the Lumora server and world browsers need from the running Minecraft version. The lists themselves
 * (servers.dat, saves/) and the server ping are version independent and live in the core; only starting a game and
 * opening vanilla screens is done by the version adapter. All methods run on the client thread.
 */
public interface BrowserPlatform {
    /** Connects to a server; a failed connection returns to the current screen. */
    void join(String address, String name);

    /** Loads the world in {@code saves/<folder>}; {@code name} is its display name (old versions store it again). */
    void playWorld(String folder, String name);

    /** Opens Minecraft's "create world" screen; it returns to the current screen. */
    void createWorld();

    /** Opens Minecraft's own multiplayer or singleplayer screen (once, without the Lumora replacement). */
    void openClassic(boolean multiplayer);

    /** Network protocol version of this Minecraft version (for the server ping). */
    int protocolVersion();
}
