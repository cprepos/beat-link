package org.deepsymmetry.beatlink.data;

/**
 * Provides notification when the track signature associated with a player changes.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class SignatureUpdate {
    /**
     * The player number for which a signature change has occurred.
     */
    public final int player;

    /**
     * The track signature now associated with the track loaded in the player. Will be {@code null} if we don't yet
     * have enough metadata about the track to compute its signature (including for a brief period after a new track
     * has been loaded while the various metadata finders are retrieving the information they need).
     */
    public final String signature;

    SignatureUpdate(int player, String signature) {
        this.player = player;
        this.signature = signature;
    }

    @Override
    public String toString() {
        return "TrackSignatureUpdate[player:" + player + ", signature:" + signature + "]";
    }
}
