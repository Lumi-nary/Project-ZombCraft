package pzcraft.pz;

import zombie.characters.BodyDamage.BodyDamage;

/**
 * PZ's BodyDamage.UpdateBoredom reads the character's current square. A zombie's attack on Steve reaches it through
 * AttackState.triggerPlayerReaction, and in the owner's 0.16.2 session Steve had no current square at that moment:
 * PZ threw a NullPointerException and left the world (B18). Until the reason the square goes null is found, this only
 * skips the boredom tick while there is no square; the rest of BodyDamage runs as before, and Steve's position is not
 * touched. Logged so the count shows how often it happens.
 */
public final class BoredomGuard {
    private static int skipped;

    private BoredomGuard() {}

    /** True = skip PZ's boredom tick for this body, because its character has no current square (B18). */
    public static boolean skipTick(BodyDamage body) {
        if (body.getParentChar() == null || body.getParentChar().getCurrentSquare() != null) return false;
        skipped++;
        if (skipped == 1 || skipped % 100 == 0) {
            Log.info("boredom tick skipped: the character has no current square (" + skipped + " so far; B18)");
        }
        return true;
    }
}
