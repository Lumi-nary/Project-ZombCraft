package pzcraft.pz;

/**
 * One of PZ's links to a Minecraft: the player link to this player's own Minecraft ({@link LinkService#LINK}) or, in a PZ
 * server, the host link to the Minecraft server ({@link HostLink#LINK}). What streams the PZ world to Minecraft (collision,
 * materials, clock, weather) is written once and given the link it feeds.
 */
interface McLink {
    /** Queues a message; false when the ring is full (send it again on a later pass). */
    boolean send(int type, byte[] payload);

    boolean connected();

    /** Changes whenever that Minecraft (re)connects or restarts: it then knows nothing of what was sent before. */
    int epoch();
}
