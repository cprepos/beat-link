package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Provides a convenient way to draw waveform detail in a user interface, including annotations like the
 * location at the current time, and cue point markers (if you supply {@link TrackMetadata} so their location
 * can be determined), and beat markers (if you also supply a {@link BeatGrid}). Can also
 * be configured to automatically update itself to reflect the state of a specified player, showing the current
 * track, playback state, and position, as long as it is able to load appropriate metadata.
 */
@SuppressWarnings("WeakerAccess")
public class WaveformDetailComponent extends JComponent {

    private static final Logger logger = LoggerFactory.getLogger(WaveformDetailComponent.class);

    /**
     * How many pixels high are the beat markers.
     */
    private static final int BEAT_MARKER_HEIGHT = 4;

    /**
     * How many pixels high are the cue markers.
     */
    private static final int CUE_MARKER_HEIGHT = 4;

    /**
     * How many pixels beyond the waveform the playback indicator extends.
     */
    private static final int VERTICAL_MARGIN = 15;

    /**
     * How many pixels wide is the current time indicator.
     */
    private static final int PLAYBACK_MARKER_WIDTH = 2;

    /**
     * The color to draw the playback position when playing; a slightly transparent white.
     */
    static final Color PLAYBACK_MARKER_PLAYING = new Color(255, 0, 0, 235);

    /**
     * The color to draw the playback position when playing; a slightly transparent red.
     */
    static final Color PLAYBACK_MARKER_STOPPED = new Color(255, 255, 255, 235);

    /**
     * The color drawn behind sections of the waveform which represent loops.
     */
    private static final Color LOOP_BACKGROUND = new Color(204, 121, 29);

   /**
     * If not zero, automatically update the waveform, position, and metadata in response to the activity of the
     * specified player number.
     */
    private final AtomicInteger monitoredPlayer = new AtomicInteger(0);

    /**
     * The waveform preview that we are drawing.
     */
    private final AtomicReference<WaveformDetail> waveform = new AtomicReference<WaveformDetail>();

    /**
     * Track the playback state for the players that have the track loaded.
     */
    private final Map<Integer, PlaybackState> playbackStateMap = new ConcurrentHashMap<Integer, PlaybackState>(4);

    /**
     * Track how many segments we average into a column of pixels; larger values zoom out, 1 is full scale.
     */
    private final AtomicInteger scale = new AtomicInteger(1);

    /**
     * Information about the track whose waveform we are drawing, so we can draw cues and memory points.
     */
    private final AtomicReference<TrackMetadata> metadata = new AtomicReference<TrackMetadata>();

    /**
     * Information about where all the beats in the track fall, so we can draw them.
     */
    private final AtomicReference<BeatGrid> beatGrid = new AtomicReference<BeatGrid>();

    /**
     * Keep track of whether we are supposed to be delegating our repaint calls to a host component.
     */
    private final AtomicReference<RepaintDelegate> repaintDelegate = new AtomicReference<RepaintDelegate>();

    /**
     * Establish a host component to which all {@link #repaint(int, int, int, int)} calls should be delegated,
     * presumably because we are being soft-loaded in a large user interface to save on memory.
     *
     * @param delegate the permanent component that can actually accumulate repaint regions, or {@code null} if
     *                 we are being hosted normally in a container, so we should use the normal repaint process.
     */
    public void setRepaintDelegate(RepaintDelegate delegate) {
        repaintDelegate.set(delegate);
    }

    /**
     * Determine whether we should use the normal repaint process, or delegate that to another component that is
     * hosting us in a soft-loaded manner to save memory.
     *
     * @param x the left edge of the region that we want to have redrawn
     * @param y the top edge of the region that we want to have redrawn
     * @param width the width of the region that we want to have redrawn
     * @param height the height of the region that we want to have redrawn
     */
    @SuppressWarnings("SameParameterValue")
    private void delegatingRepaint(int x, int y, int width, int height) {
        final RepaintDelegate delegate = repaintDelegate.get();
        if (delegate != null) {
            delegate.repaint(x, y, width, height);
        } else {
            repaint(x, y, width, height);
        }
    }

    /**
     * Helper method to mark the parts of the component that need repainting due to a change to the
     * tracked playback positions.
     *
     * @param oldState the old position of a marker being moved, or {@code null} if we are adding a marker
     * @param newState the new position of a marker being moved, or {@code null} if we are removing a marker
     */
    private void repaintDueToPlaybackStateChange(PlaybackState oldState, PlaybackState newState) {
        // Refresh where the specific marker was moved from and/or to.
        if (oldState != null) {
            final int left = millisecondsToX(oldState.position) - 6;
            final int right = millisecondsToX(oldState.position) + 6;
            delegatingRepaint(left, 0, right - left, getHeight());
        }
        if (newState != null) {
            final int left = millisecondsToX(newState.position) - 6;
            final int right = millisecondsToX(newState.position) + 6;
            delegatingRepaint(left, 0, right - left, getHeight());
        }
    }

    /**
     * Set the current playback state for a player.
     *
     * Will cause part of the component to be redrawn if the player state has
     * changed (and we have the {@link TrackMetadata} we need to translate the time into a position in the
     * component). This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param player the player number whose playback state is being recorded
     * @param position the current playback position of that player in milliseconds
     * @param playing whether the player is actively playing the track
     *
     * @throws IllegalStateException if the component is configured to monitor a player, and this is called
     *         with state for a different player
     * @throws IllegalArgumentException if player is less than one
     *
     * @since 0.5.0
     */
    public synchronized void setPlaybackState(int player, long position, boolean playing) {
        if (getMonitoredPlayer() != 0 && player != getMonitoredPlayer()) {
            throw new IllegalStateException("Cannot setPlaybackState for another player when monitoring player " + getMonitoredPlayer());
        }
        if (player < 1) {
            throw new IllegalArgumentException("player must be positive");
        }
        PlaybackState newState = new PlaybackState(player, position, playing);
        PlaybackState oldState = playbackStateMap.put(player, newState);
        repaintDueToPlaybackStateChange(oldState, newState);
    }

    /**
     * Clear the playback state stored for a player, such as when it has unloaded the track.
     *
     * @param player the player number whose playback state is no longer valid
     * @since 0.5.0
     */
    public synchronized void clearPlaybackState(int player) {
        PlaybackState oldState = playbackStateMap.remove(player);
        repaintDueToPlaybackStateChange(oldState, null);
    }

    /**
     * Removes all stored playback state.
     * @since 0.5.0
     */
    public synchronized void clearPlaybackState() {
        for (PlaybackState state : playbackStateMap.values()) {
            clearPlaybackState(state.player);
        }
    }

    /**
     * Look up the playback state recorded for a particular player.
     *
     * @param player the player number whose playback state information is desired
     * @return the corresponding playback state, if any has been stored
     * @since 0.5.0
     */
    public PlaybackState getPlaybackState(int player) {
        return playbackStateMap.get(player);
    }

    /**
     * Look up all recorded playback state information.
     *
     * @return the playback state recorded for any player
     * @since 0.5.0
     */
    public Set<PlaybackState> getPlaybackState() {
        Set<PlaybackState> result = new HashSet<PlaybackState>(playbackStateMap.values());
        return Collections.unmodifiableSet(result);
    }

    /**
     * Helper method to find the single current playback state when used in single-player mode.
     *
     * @return either the single stored playback state
     */
    private PlaybackState currentSimpleState() {
        if (!playbackStateMap.isEmpty()) {  // Avoid exceptions during animation loop shutdown.
            return playbackStateMap.values().iterator().next();
        }
        return null;
    }

    /**
     * Set the current playback position. This method can only be used in situations where the component is
     * tied to a single player, and therefore always has a single playback position.
     *
     * Will cause part of the component to be redrawn if the position has
     * changed. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param milliseconds how far into the track has been played
     *
     * @see #setPlaybackState
     */
    private void setPlaybackPosition(long milliseconds) {
        PlaybackState oldState = currentSimpleState();
        if (oldState != null && oldState.position != milliseconds) {
            setPlaybackState(oldState.player, milliseconds, oldState.playing);
        }
    }

    /**
     * Set the zoom scale of the view. a value of (the smallest allowed) draws the waveform at full scale.
     * Larger values combine more and more segments into a single column of pixels, zooming out to see more at once.
     *
     * @param scale the number of waveform segments that should be averaged into a single column of pixels
     *
     * @throws IllegalArgumentException if scale is less than 1 or greater than 256
     */
    public void setScale(int scale) {
        if ((scale < 1) || (scale > 256)) {
            throw new IllegalArgumentException("Scale must be between 1 and 256");
        }
        int oldScale = this.scale.getAndSet(scale);
        if (oldScale != scale) {
            repaint();
        }
    }

    /**
     * Set whether the player holding the waveform is playing, which changes the indicator color to white from red.
     * This method can only be used in situations where the component is tied to a single player, and therefore has
     * a single playback position.
     *
     * @param playing if {@code true}, draw the position marker in white, otherwise red
     *
     * @see #setPlaybackState
     */
    private void setPlaying(boolean playing) {
        PlaybackState oldState = currentSimpleState();
        if (oldState != null && oldState.playing != playing) {
            setPlaybackState(oldState.player, oldState.position, playing);
        }
    }

    /**
     * Change the waveform preview being drawn. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param waveform the waveform detail to display
     * @param metadata information about the track whose waveform we are drawing, so we can draw cue and memory points
     * @param beatGrid the locations of the beats, so they can be drawn
     */
    public void setWaveform(WaveformDetail waveform, TrackMetadata metadata, BeatGrid beatGrid) {
        this.waveform.set(waveform);
        this.metadata.set(metadata);
        this.beatGrid.set(beatGrid);
        clearPlaybackState();
        repaint();
    }

    /**
     * Used to signal our animation thread to stop when we are no longer monitoring a player.
     */
    private final AtomicBoolean animating = new AtomicBoolean(false);

    /**
     * Configures the player whose current track waveforms and status will automatically be reflected. Whenever a new
     * track is loaded on that player, the waveform and metadata will be updated, and the current playback position and
     * state of the player will be reflected by the component.
     *
     * @param player the player number to monitor, or zero if monitoring should stop
     */
    public synchronized void setMonitoredPlayer(final int player) {
        if (player < 0) {
            throw new IllegalArgumentException("player cannot be negative");
        }
        clearPlaybackState();
        monitoredPlayer.set(player);
        if (player > 0) {  // Start monitoring the specified player
            setPlaybackState(player, 0, false);  // Start with default values for required simple state.
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            if (MetadataFinder.getInstance().isRunning()) {
                metadata.set(MetadataFinder.getInstance().getLatestMetadataFor(player));
            } else {
                metadata.set(null);
            }
            WaveformFinder.getInstance().addWaveformListener(waveformListener);
            if (WaveformFinder.getInstance().isRunning() && WaveformFinder.getInstance().isFindingDetails()) {
                waveform.set(WaveformFinder.getInstance().getLatestDetailFor(player));
            } else {
                waveform.set(null);
            }
            BeatGridFinder.getInstance().addBeatGridListener(beatGridListener);
            if (BeatGridFinder.getInstance().isRunning()) {
                beatGrid.set(BeatGridFinder.getInstance().getLatestBeatGridFor(player));
            } else {
                beatGrid.set(null);
            }
            try {
                TimeFinder.getInstance().start();
                if (!animating.getAndSet(true)) {
                    // Create the thread to update our position smoothly as the track plays
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            int lastPosition = getSegmentForX(0);
                            while (animating.get()) {
                                try {
                                    Thread.sleep(33);  // Animate at 30 fps
                                } catch (InterruptedException e) {
                                    logger.warn("Waveform animation thread interrupted; ending");
                                    animating.set(false);
                                }
                                setPlaybackPosition(TimeFinder.getInstance().getTimeFor(getMonitoredPlayer()));
                                int newPosition = getSegmentForX(0);
                                if (lastPosition != newPosition) {
                                    lastPosition = newPosition;
                                    repaint();
                                }
                            }
                        }
                    }).start();
                }
            } catch (Exception e) {
                logger.error("Unable to start the TimeFinder to animate the waveform detail view");
                animating.set(false);
            }
        } else {  // Stop monitoring any player
            animating.set(false);
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            WaveformFinder.getInstance().removeWaveformListener(waveformListener);
            metadata.set(null);
            waveform.set(null);
            beatGrid.set(null);
        }
        repaint();
    }

    /**
     * See which player is having its state tracked automatically by the component, if any.
     *
     * @return the player number being monitored, or zero if none
     */
    public int getMonitoredPlayer() {
        return monitoredPlayer.get();
    }

    /**
     * Reacts to changes in the track metadata associated with the player we are monitoring.
     */
    private final TrackMetadataListener metadataListener = new TrackMetadataListener() {
        @Override
        public void metadataChanged(TrackMetadataUpdate update) {
            if (update.player == getMonitoredPlayer()) {
                metadata.set(update.metadata);
                repaint();
            }
        }
    };

    /**
     * Reacts to changes in the waveform associated with the player we are monitoring.
     */
    private final WaveformListener waveformListener = new WaveformListener() {
        @Override
        public void previewChanged(WaveformPreviewUpdate update) {
            // Nothing to do.
        }

        @Override
        public void detailChanged(WaveformDetailUpdate update) {
            logger.debug("Got waveform detail update: {}", update);
            if (update.player == getMonitoredPlayer()) {
                waveform.set(update.detail);
                repaint();
            }
        }
    };

    /**
     * Reacts to changes in the beat grid associated with the player we are monitoring.
     */
    private final BeatGridListener beatGridListener = new BeatGridListener() {
        @Override
        public void beatGridChanged(BeatGridUpdate update) {
            if (update.player == getMonitoredPlayer()) {
                beatGrid.set(update.beatGrid);
                repaint();
            }
        }
    };

    /**
     * Reacts to player status updates to reflect the current playback state.
     */
    private final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            if ((update instanceof CdjStatus) && (update.getDeviceNumber() == getMonitoredPlayer()) &&
                    (metadata.get() != null) && (beatGrid.get() != null)) {
                CdjStatus status = (CdjStatus) update;
                setPlaying(status.isPlaying());
            }
        }
    };

    /**
     * Create a view which updates itself to reflect the track loaded on a particular player, and that player's
     * playback progress.
     *
     * @param player the player number to monitor, or zero if it should start out monitoring no player
     */
    public WaveformDetailComponent(int player) {
        setMonitoredPlayer(player);
    }

    /**
     * Create a view which draws a specific waveform, even if it is not currently loaded in a player.
     *
     * @param waveform the waveform detail to display
     * @param metadata information about the track whose waveform we are drawing, so we can draw cues and memory points
     * @param beatGrid the locations of the beats, so they can be drawn
     */
    public WaveformDetailComponent(WaveformDetail waveform, TrackMetadata metadata, BeatGrid beatGrid) {
        this.waveform.set(waveform);
        this.metadata.set(metadata);
        this.beatGrid.set(beatGrid);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(300, 92);
    }

    /**
     * Look up the playback state that has reached furthest in the track, but give playing players priority over stopped players.
     * This is used to choose the scroll center when auto-scrolling is active.
     *
     * @return the playback state, if any, with the highest playing {@link PlaybackState#position} value
     */
    public PlaybackState getFurthestPlaybackState() {
        PlaybackState result = null;
        for (PlaybackState state : playbackStateMap.values()) {
            if (result == null || (!result.playing && state.playing) ||
                    (result.position < state.position) && (state.playing || !result.playing)) {
                result = state;
            }
        }
        return result;
    }


    /**
     * Figure out the starting waveform segment that corresponds to the specified coordinate in the window.

     * @param x the column being drawn
     *
     * @return the offset into the waveform at the current scale and playback time that should be drawn there
     */
    private int getSegmentForX(int x) {
        int playHead = (x - (getWidth() / 2));
        // TODO: Make auto-scroll optional.
        int offset = Util.timeToHalfFrame(getFurthestPlaybackState().position) / scale.get();
        return  (playHead + offset) * scale.get();
    }

    /**
     * Converts a time in milliseconds to the appropriate x coordinate for drawing something at that time.
     *
     * @param milliseconds the time at which something should be drawn
     *
     * @return the component x coordinate at which it should be drawn
     */
    private int millisecondsToX(long milliseconds) {
        int playHead = (getWidth() / 2) + 2;
        // TODO: Make auto-scroll optional.
        long offset = milliseconds - getFurthestPlaybackState().position;
        return playHead + (Util.timeToHalfFrame(offset) / scale.get());
    }

    /**
     * The largest scale at which we will draw individual beat markers; above this we show only bars.
     */
    private static final int MAX_BEAT_SCALE = 9;

    /**
     * Determine the color to use to draw a cue list entry. Hot cues are green, ordinary memory points are red,
     * and loops are orange.
     *
     * @param entry the entry being drawn
     *
     * @return the color with which it should be represented.
     */
    public static Color cueColor(CueList.Entry entry) {
        if (entry.hotCueNumber > 0) {
            return Color.GREEN;
        }
        if (entry.isLoop) {
            return Color.ORANGE;
        }
        return Color.RED;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clipRect = g.getClipBounds();  // We only need to draw the part that is visible or dirty
        g.setColor(Color.BLACK);  // Black out the background
        g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

        // See if we have a cue list
        CueList cueList = null;
        if (metadata.get() != null) {
            cueList = metadata.get().getCueList();
        }

        // Draw the loop regions of any visible loops
        final int axis = getHeight() / 2;
        final int maxHeight = axis - VERTICAL_MARGIN;
        if (cueList != null) {
            g.setColor(LOOP_BACKGROUND);
            for (CueList.Entry entry : cueList.entries) {
                if (entry.isLoop) {
                    final int start = millisecondsToX(entry.cueTime);
                    final int end = millisecondsToX(entry.loopTime);
                    g.fillRect(start, axis - maxHeight, end - start, maxHeight * 2);
                }
            }
        }

        int lastBeat = 0;
        if (beatGrid.get() != null) {  // Find what beat was represented by the column just before the first we draw.
            lastBeat = beatGrid.get().findBeatAtTime(Util.halfFrameToTime(getSegmentForX(clipRect.x - 1)));
        }
        for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {
            final int segment = getSegmentForX(x);
            if (waveform.get() != null) { // Drawing the waveform itself
                if ((segment >= 0) && (segment < waveform.get().getFrameCount())) {
                    g.setColor(waveform.get().segmentColor(segment, scale.get()));
                    final int height = (waveform.get().segmentHeight(segment, scale.get()) * maxHeight) / 31;
                    g.drawLine(x, axis - height, x, axis + height);
                }
            }
            if (beatGrid.get() != null) {  // Draw the beat markers
                int inBeat = beatGrid.get().findBeatAtTime(Util.halfFrameToTime(segment));
                if ((inBeat > 0) && (inBeat != lastBeat)) {  // Start of a new beat, so prepare to draw it
                    final int beatWithinBar = beatGrid.get().getBeatWithinBar(inBeat);
                    if (scale.get() <= MAX_BEAT_SCALE || beatWithinBar == 1) {
                        // Once scale gets large enough, we only draw the down beats, like CDJs.
                        g.setColor((beatWithinBar == 1) ? Color.RED : Color.WHITE);
                        g.drawLine(x, axis - maxHeight - 2 - BEAT_MARKER_HEIGHT, x, axis - maxHeight - 2);
                        g.drawLine(x, axis + maxHeight + 2, x, axis + maxHeight + BEAT_MARKER_HEIGHT + 2);
                    }
                    lastBeat = inBeat;
                }
            }
        }

        // Draw the cue and memory point markers, first the memory cues and then the hot cues, since some are in
        // the same place and we want the hot cues to stand out.
        if (cueList != null) {
            drawCueList(g, clipRect, cueList, axis, maxHeight, false);
            drawCueList(g, clipRect, cueList, axis, maxHeight, true);
        }

        // Draw the non-playing markers first, so the playing ones will be seen if they are in the same spot.
        g.setColor(WaveformDetailComponent.PLAYBACK_MARKER_STOPPED);
        for (PlaybackState state : playbackStateMap.values()) {
            if (!state.playing) {
                g.fillRect((getWidth() / 2) - 1, 0, PLAYBACK_MARKER_WIDTH, getHeight());
            }
        }

        // Then draw the playing markers on top of the non-playing ones.
        g.setColor(WaveformDetailComponent.PLAYBACK_MARKER_PLAYING);
        for (PlaybackState state : playbackStateMap.values()) {
            if (state.playing) {
                g.fillRect((getWidth() / 2) - 1, 0, PLAYBACK_MARKER_WIDTH, getHeight());
            }
        }
    }

    /**
     * Draw the visible memory cue points or hot cues.
     *
     * @param g the graphics object in which we are being rendered
     * @param clipRect the region that is being currently rendered
     * @param cueList the cues to  be drawn
     * @param axis the base on which the waveform is being drawn
     * @param maxHeight the highest waveform segment
     * @param hot true if we should draw hot cues, otherwise we draw memory points
     */
    private void drawCueList(Graphics g, Rectangle clipRect, CueList cueList, int axis, int maxHeight, boolean hot) {
        for (CueList.Entry entry : cueList.entries) {
            if ((hot && entry.hotCueNumber > 0) || (entry.hotCueNumber == 0 && !hot)) {
                final int x = millisecondsToX(entry.cueTime);
                if ((x > clipRect.x - 4) && (x < clipRect.x + clipRect.width + 4)) {
                    g.setColor(cueColor(entry));
                    for (int i = 0; i < 4; i++) {
                        g.drawLine(x - 3 + i, axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i,
                                x + 3 - i, axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return"WaveformDetailComponent[metadata=" + metadata.get() + ", waveform=" + waveform.get() + ", beatGrid=" +
                beatGrid.get() + ", playbackStateMap=" + playbackStateMap + ", monitoredPlayer=" +
                getMonitoredPlayer() + "]";
    }
}
