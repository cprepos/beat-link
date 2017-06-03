package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.<p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class MetadataFinder {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class.getName());

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any. If there is
     * an appropriate metadata cache, will use that, otherwise makes a query to the players dbserver.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     *
     * @return the metadata that was obtained, if any
     */
    @SuppressWarnings("WeakerAccess")
    public static TrackMetadata requestMetadataFrom(CdjStatus status) {
        if (status.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK || status.getRekordboxId() == 0) {
            return null;
        }
        return requestMetadataFrom(status.getTrackSourcePlayer(), status.getTrackSourceSlot(), status.getRekordboxId());
    }


    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID,
     * unless we have a metadata cache available for the specified media slot, in which case that will be used instead.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     *
     * @return the metadata, if any
     *
     * @throws IllegalStateException if a metadata cache is being created amd we need to talk to the CDJs
     */
    @SuppressWarnings("WeakerAccess")
    public static TrackMetadata requestMetadataFrom(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        return requestMetadataInternal(player, slot, rekordboxId, false);
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID,
     * using cached media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      metadata updates will use available caches only
     *
     * @return the metadata found, if any
     *
     * @throws IllegalStateException if a metadata cache is being created amd we need to talk to the CDJs
     */
    private static TrackMetadata requestMetadataInternal(final int player, final CdjStatus.TrackSourceSlot slot,
                                                         final int rekordboxId, boolean failIfPassive) {
        // First check if we are using cached data for this request
        ZipFile cache = getMetadataCache(player, slot);
        if (cache != null) {
            return getCachedMetadata(cache, rekordboxId);
        }

        if (passive && failIfPassive) {
            return null;
        }

        // TODO: Once we understand and track cue points, keep an in-memory cache of any loaded hot-cue tracks.

        ConnectionManager.ClientTask<TrackMetadata> task = new ConnectionManager.ClientTask<TrackMetadata>() {
            @Override
            public TrackMetadata useClient(Client client) throws Exception {
                return queryMetadata(rekordboxId, slot, client);
            }
        };

        try {
            return ConnectionManager.invokeWithClientSession(player, task, "requesting metadata");
        } catch (Exception e) {
            logger.error("Problem requesting metadata, returning null", e);
        }
        return null;
    }

    /**
     * Request metadata for a specific track ID, given a connection to a player that has already been set up.
     * Separated into its own method so it could be used multiple times with the same connection when gathering
     * all track metadata.
     *
     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved metadata, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     */
    private static TrackMetadata queryMetadata(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.METADATA_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(rekordboxId));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return null;
        }

        // Gather all the metadata menu items
        final List<Message> items = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
        TrackMetadata result = new TrackMetadata(rekordboxId, items);
        if (result.getArtworkId() != 0) {
            result = result.withArtwork(requestArtwork(result.getArtworkId(), slot, client));
        }
        return result;
    }

    /**
     * Requests the beat grid for a specific track ID, given a connection to a player that has already been set up.

     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved beat grid, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     */
    private static BeatGrid getBeatGrid(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.BEAT_GRID_REQ, null,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.BEAT_GRID) {
            return new BeatGrid(response);
        }
        logger.error("Unexpected response type when requesting beat grid: {}", response);
        return null;
    }

    /**
     * Request the list of all tracks in the specified slot, given a connection to a player that has already been
     * set up.
     *
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved track list entry items
     *
     * @throws IOException if there is a communication problem
     */
    private static List<Message> getFullTrackList(CdjStatus.TrackSourceSlot slot, Client client)
        throws IOException {
        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.TRACK_LIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(0));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return Collections.emptyList();
        }

        // Gather all the metadata menu items
        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
    }


    /**
     * Look up track metadata from a cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose metadata is desired
     *
     * @return the cached metadata, including album art (if available), or {@code null}
     */
    private static TrackMetadata getCachedMetadata(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getMetadataEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                List<Message> items = new LinkedList<Message>();
                Message current = Message.read(is);
                while (current.messageType.getValue() == Message.KnownType.MENU_ITEM.protocolValue) {
                    items.add(current);
                    current = Message.read(is);
                }
                TrackMetadata result = new TrackMetadata(rekordboxId, items);
                try {
                    is.close();
                } catch (Exception e) {
                    is = null;
                    logger.error("Problem closing Zip File input stream after reading metadata entry", e);
                }
                if (result.getArtworkId() != 0) {
                    entry = cache.getEntry(getArtworkEntryName(result));
                    if (entry != null) {
                        is = new DataInputStream(cache.getInputStream(entry));
                        try {
                            byte[] imageBytes = new byte[(int)entry.getSize()];
                            is.readFully(imageBytes);
                            result = result.withArtwork(ByteBuffer.wrap(imageBytes).asReadOnlyBuffer());
                        } catch (Exception e) {
                            logger.error("Problem reading artwork from metadata cache, leaving as null", e);
                        }
                    }
                }
                return result;
            } catch (IOException e) {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e2) {
                        logger.error("Problem closing ZipFile input stream after exception", e2);
                    }
                }
                logger.error("Problem reading metadata from cache file, returning null", e);
            }
        }
        return null;
    }

    /**
     * Look up a beat grid in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose beat grid is desired
     *
     * @return the cached beat grid (if available), or {@code null}
     */
    private static BeatGrid getCachedBeatGrid(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getBeatGridEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                byte[] gridBytes = new byte[(int)entry.getSize()];
                is.readFully(gridBytes);
                try {
                    is.close();
                } catch (Exception e) {
                    is = null;
                    logger.error("Problem closing Zip File input stream after reading beat grid entry", e);
                }
                return new BeatGrid(ByteBuffer.wrap(gridBytes).asReadOnlyBuffer());
            } catch (IOException e) {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e2) {
                        logger.error("Problem closing ZipFile input stream after exception", e2);
                    }
                }
                logger.error("Problem reading beat grid from cache file, returning null", e);
            }
        }
        return null;
    }

    /**
     * Ask the connected dbserver for the playlist entries of the specified playlist (if {@code folder} is {@code false},
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}.
     * Pulled into a separate method so it can be used from multiple different client transactions.
     *
     * @param slot the slot in which the track can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 5.10.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     * @param client the dbserver client that is communicating with the appropriate player

     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder

     * @throws IOException if there is a problem communicating
     */
    private static List<Message> getPlaylistItems(CdjStatus.TrackSourceSlot slot, int sortOrder, int playlistOrFolderId,
                                                  boolean folder, Client client) throws IOException {
        Message response = client.menuRequest(Message.KnownType.PLAYLIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(sortOrder), new NumberField(playlistOrFolderId), new NumberField(folder? 1 : 0));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return Collections.emptyList();
        }

        // Gather all the metadata menu items
        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
    }

    /**
     * Ask the specified player for the playlist entries of the specified playlist (if {@code folder} is {@code false},
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 5.10.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     *
     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder
     *
     * @throws Exception if there is a problem obtaining the playlist information
     */
    public static List<Message> requestPlaylistItemsFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                                         final int sortOrder, final int playlistOrFolderId,
                                                         final boolean folder)
            throws Exception {
        ConnectionManager.ClientTask<List<Message>> task = new ConnectionManager.ClientTask<List<Message>>() {
            @Override
            public List<Message> useClient(Client client) throws Exception {
               return getPlaylistItems(slot, sortOrder, playlistOrFolderId, folder, client);
            }
        };

        return ConnectionManager.invokeWithClientSession(player, task, "requesting playlist information");
    }

    /**
     * Ask the specified player for the beat grid of the track in the specified slot with the specified rekordbox ID.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     *
     * @return the beat grid, if any
     *
     * @throws Exception if there is a problem getting the beat grid information
     */
    public static BeatGrid requestBeatGridFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                               final int rekordboxId)
            throws Exception {

        // First check if we are using cached data for this request
        ZipFile cache = getMetadataCache(player, slot);
        if (cache != null) {
            return getCachedBeatGrid(cache, rekordboxId);
        }

        ConnectionManager.ClientTask<BeatGrid> task = new ConnectionManager.ClientTask<BeatGrid>() {
            @Override
            public BeatGrid useClient(Client client) throws Exception {
                return getBeatGrid(rekordboxId, slot, client);
            }
        };

        return ConnectionManager.invokeWithClientSession(player, task, "requesting beat grid");
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced.
     *
     * @param player the player number whose media library is to have its metadata cached
     * @param slot the slot in which the media to be cached can be found
     * @param playlistId the id of playlist to be cached, or 0 of all tracks should be cached
     * @param cache the file into which the metadata cache should be written
     *
     * @throws Exception if there is a problem communicating with the player or writing the cache file.
     */
    public static void createMetadataCache(int player, CdjStatus.TrackSourceSlot slot, int playlistId, File cache)
            throws Exception {
        createMetadataCache(player, slot, playlistId, cache, null);
    }

    /**
     * The root under which all zip file entries will be created in our cache metadata files.
     */
    private static final String CACHE_PREFIX = "BLTMetaCache/";

    /**
     * The file entry whose content will be the cache format identifier.
     */
    private static final String CACHE_FORMAT_ENTRY = CACHE_PREFIX + "version";

    /**
     * The prefix for cache file entries that will store track metadata.
     */
    private static final String CACHE_METADATA_ENTRY_PREFIX = CACHE_PREFIX + "metadata/";

    /**
     * The prefix for cache file entries that will store album art.
     */
    private static final String CACHE_ART_ENTRY_PREFIX = CACHE_PREFIX + "artwork/";

    /**
     * The prefix for cache file entries that will store beat grids.
     */
    private static final String CACHE_BEAT_GRID_ENTRY_PREFIX = CACHE_PREFIX + "beatgrid/";

    /**
     * The comment string used to identify a ZIP file as one of our metadata caches.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String CACHE_FORMAT_IDENTIFIER = "BeatLink Metadata Cache version 1";

    /**
     * Used to mark the end of the metadata items in each cache entry, just like when reading from the server.
     */
    private static final Message MENU_FOOTER_MESSAGE = new Message(0, Message.KnownType.MENU_FOOTER);

    /**
     * Finish the process of copying a list of tracks to a metadata cache, once they have been listed. This code
     * is shared between the implementations that work with the full track list and with playlists.
     *
     * @param trackListEntries the list of menu items identifying which tracks need to be copied to the metadata
     *                         cache
     * @param client the connection to the dbserver on the player whose metadata is being cached
     * @param slot the slot in which the media to be cached can be found
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws IOException if there is a problem communicating with the player or writing the cache file.
     */
    private static void copyTracksToCache(List<Message> trackListEntries, Client client, CdjStatus.TrackSourceSlot slot,
                                   File cache, MetadataCreationUpdateListener listener)
        throws IOException {
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        ZipOutputStream zos = null;
        WritableByteChannel channel = null;
        final Set<Integer> artworkAdded = new HashSet<Integer>();
        try {
            fos = new FileOutputStream(cache);
            bos = new BufferedOutputStream(fos);
            zos = new ZipOutputStream(bos);
            zos.setMethod(ZipOutputStream.DEFLATED);

            // Add a marker so we can recognize this as a metadata archive. I would use the ZipFile comment, but
            // that is not available until Java 7, and Beat Link is supposed to be backwards compatible with Java 6.
            ZipEntry zipEntry = new ZipEntry(CACHE_FORMAT_ENTRY);
            zos.putNextEntry(zipEntry);
            zos.write(CACHE_FORMAT_IDENTIFIER.getBytes("UTF-8"));

            // Write the actual metadata entries
            channel = Channels.newChannel(zos);
            final int totalToCopy = trackListEntries.size();
            int tracksCopied = 0;

            for (Message entry : trackListEntries) {
                if (entry.getMenuItemType() != Message.MenuItemType.TRACK_LIST_ENTRY) {
                    throw new IOException("Received unexpected item type. Needed TRACK_LIST_ENTRY, got: " + entry);
                }
                int rekordboxId = (int)((NumberField)entry.arguments.get(1)).getValue();
                TrackMetadata track = queryMetadata(rekordboxId, slot, client);

                if (track != null) {
                    logger.debug("Adding metadata with ID {}", rekordboxId);
                    zipEntry = new ZipEntry(getMetadataEntryName(rekordboxId));
                    zos.putNextEntry(zipEntry);
                    for (Message metadataItem : track.rawItems) {
                        metadataItem.write(channel);
                    }
                    MENU_FOOTER_MESSAGE.write(channel);  // So we know to stop reading
                } else {
                    logger.warn("Unable to retrieve metadata with ID {}", rekordboxId);
                }

                // Now the album art, if any
                if (track != null && track.getRawArtwork() != null && !artworkAdded.contains(track.getArtworkId())) {
                    logger.debug("Adding artwork with ID {}", track.getArtworkId());
                    zipEntry = new ZipEntry(getArtworkEntryName(track));
                    zos.putNextEntry(zipEntry);
                    Util.writeFully(track.getRawArtwork(), channel);
                    artworkAdded.add(track.getArtworkId());
                }

                BeatGrid beatGrid = getBeatGrid(rekordboxId, slot, client);
                if (beatGrid != null) {
                    logger.debug("Adding beat grid with ID {}", rekordboxId);
                    zipEntry = new ZipEntry(getBeatGridEntryName(rekordboxId));
                    zos.putNextEntry(zipEntry);
                    Util.writeFully(beatGrid.getRawData(), channel);
                }

                // TODO: Include waveforms (once supported), etc.
                if (listener != null) {
                    if (!listener.cacheUpdateContinuing(track, ++tracksCopied, totalToCopy)) {
                        logger.info("Track metadata cache creation canceled by listener");
                        if (!cache.delete()) {
                            logger.warn("Unable to delete cache metadata file, {}", cache);
                        }
                        return;
                    }
                }
            }
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing byte channel for writing to metadata cache", e);
            }
            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing Zip Output Stream of metadata cache", e);
            }
            try {
                if (bos != null) {
                    bos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing Buffered Output Stream of metadata cache", e);
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing File Output Stream of metadata cache", e);
            }
        }
    }

    /**
     * Names the appropriate zip file entry for caching a track's metadata.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's metadata should be stored
     */
    private static String getMetadataEntryName(int rekordboxId) {
        return CACHE_METADATA_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's album art.
     *
     * @param track the track being cached or looked up
     *
     * @return the name of entry where that track's artwork should be stored
     */
    private static String getArtworkEntryName(TrackMetadata track) {
        return CACHE_ART_ENTRY_PREFIX + track.getArtworkId() + ".jpg";
    }

    /**
     * Names the appropriate zip file entry for caching a track's beat grid.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's beat grid should be stored
     */
    private static String getBeatGridEntryName(int rekordboxId) {
        return CACHE_BEAT_GRID_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced. If a non-{@code null} {@code listener} is
     * supplied, its {@link MetadataCreationUpdateListener#cacheUpdateContinuing(TrackMetadata, int, int)} method
     * will be called after each track is added to the cache, allowing it to display progress updates to the user,
     * and to continue or cancel the process by returning {@code true} or {@code false}.
     *
     * Because this takes a huge amount of time relative to CDJ status updates, it can only be performed while
     * the MetadataFinder is in passive mode.
     *
     * @param player the player number whose media library is to have its metadata cached
     * @param slot the slot in which the media to be cached can be found
     * @param playlistId the id of playlist to be cached, or 0 of all tracks should be cached
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws Exception if there is a problem communicating with the player or writing the cache file
     */
    @SuppressWarnings({"SameParameterValue", "WeakerAccess"})
    public static void createMetadataCache(final int player, final CdjStatus.TrackSourceSlot slot, final int playlistId,
                                           final File cache, final MetadataCreationUpdateListener listener)
            throws Exception {
        ConnectionManager.ClientTask<Object> task = new ConnectionManager.ClientTask<Object>() {
            @Override
            public Object useClient(Client client) throws Exception {
                final List<Message> trackList;
                if (playlistId == 0) {
                    trackList = getFullTrackList(slot, client);
                } else {
                    trackList = getPlaylistItems(slot, 0, playlistId, false, client);
                }
                copyTracksToCache(trackList, client, slot, cache, listener);
                return null;
            }
        };

        if (!cache.delete()) {
            logger.warn("Unable to delete cache file, {}", cache);
        }
        ConnectionManager.invokeWithClientSession(player, task, "building metadata cache");
    }

    /**
     * Request the artwork associated with a track whose metadata is being retrieved.
     *
     * @param artworkId identifies the album art to retrieve
     * @param slot the slot identifier from which the track was loaded
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the track's artwork, or null if none is available
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private static ByteBuffer requestArtwork(int artworkId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the artwork request
        Message response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField((long)artworkId));

        // Create an image from the response bytes
        return ((BinaryField)response.arguments.get(3)).getValue();
    }

    /**
     * Keeps track of the current metadata known for each player.
     */
    private static final Map<Integer, TrackMetadata> metadata = new HashMap<Integer, TrackMetadata>();

    /**
     * Keeps track of the previous update from each player that we retrieved metadata about, to check whether a new
     * track has been loaded.
     */
    private static final Map<InetAddress, CdjStatus> lastUpdates = new HashMap<InetAddress, CdjStatus>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static final LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<CdjStatus>(100);

    /**
     * Our update listener just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            logger.debug("Received device update {}", update);
            if (update instanceof CdjStatus) {
                if (!pendingUpdates.offerLast((CdjStatus)update)) {
                    logger.warn("Discarding CDJ update because our queue is backed up.");
                }
            }
        }
    };


    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private static final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.debug("Currently nothing for MetaDataListener to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            clearMetadata(announcement);
            detachMetadataCache(announcement.getNumber(), CdjStatus.TrackSourceSlot.SD_SLOT);
            detachMetadataCache(announcement.getNumber(), CdjStatus.TrackSourceSlot.USB_SLOT);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being sought for all active players
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * Indicates whether we should use metadata only from caches, never actively requesting it from a player.
     */
    private static boolean passive = false;

    /**
     * Check whether we are configured to use metadata only from caches, never actively requesting it from a player.
     *
     * @return {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested from
     *         a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized boolean isPassive() {
        return passive;
    }

    /**
     * Set whether we are configured to use metadata only from caches, never actively requesting it from a player.
     *
     * @param passive {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested
     *                from a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized void setPassive(boolean passive) {
        MetadataFinder.passive = passive;
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out, and alert
     * any listeners.
     *
     * @param update the update which means we can have no metadata for the associated player
     */
    private static synchronized void clearMetadata(CdjStatus update) {
        metadata.remove(update.deviceNumber);
        lastUpdates.remove(update.address);
        deliverTrackMetadataUpdate(update.deviceNumber, null);
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its metadata.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private static synchronized void clearMetadata(DeviceAnnouncement announcement) {
        metadata.remove(announcement.getNumber());
        lastUpdates.remove(announcement.getAddress());
    }

    /**
     * We have obtained metadata for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private static synchronized void updateMetadata(CdjStatus update, TrackMetadata data) {
        metadata.put(update.deviceNumber, data);
        lastUpdates.put(update.address, update);
        deliverTrackMetadataUpdate(update.deviceNumber, data);
    }

    /**
     * Get all currently known metadata.
     *
     * @return the track information reported by all current players
     */
    public static synchronized Map<Integer, TrackMetadata> getLatestMetadata() {
        return Collections.unmodifiableMap(new TreeMap<Integer, TrackMetadata>(metadata));
    }

    /**
     * Look up the track metadata we have for a given player number.
     *
     * @param player the device number whose track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized TrackMetadata getLatestMetadataFor(int player) {
        return metadata.get(player);
    }

    /**
     * Look up the track metadata we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.deviceNumber);
    }

    /**
     * Keep track of the devices we are currently trying to get metadata from in response to status updates.
     */
    private static final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of any metadata caches that have been attached for the SD slots of players on the network,
     * keyed by player number.
     */
    private final static Map<Integer, ZipFile> sdMetadataCaches = new ConcurrentHashMap<Integer, ZipFile>();

    /**
     * Keeps track of any metadata caches that have been attached for the USB slots of players on the network,
     * keyed by player number.
     */
    private final static Map<Integer, ZipFile> usbMetadataCaches = new ConcurrentHashMap<Integer, ZipFile>();

    /**
     * Attach a metadata cache file to a particular player media slot, so the cache will be used instead of querying
     * the player for metadata. This supports operation with metadata during shows where DJs are using all four player
     * numbers and heavily cross-linking between them.
     *
     * If the media is ejected from that player slot, the cache will be detached.
     *
     * @param player the player number for which a metadata cache is to be attached
     * @param slot the media slot to which a meta data cache is to be attached
     * @param cache the metadata cache to be attached
     *
     * @throws IOException if there is a problem reading the cache file
     * @throws IllegalArgumentException if an invalid player number or slot is supplied
     * @throws IllegalStateException if the metadata finder is not running
     */
    public static void attachMetadataCache(int player, CdjStatus.TrackSourceSlot slot, File cache)
            throws IOException {
        if (!isRunning()) {
            throw new IllegalStateException("attachMetadataCache() can't be used if MetadataFinder is not running");
        }
        if (player < 1 || player > 4 || DeviceFinder.getLatestAnnouncementFrom(player) == null) {
            throw new IllegalArgumentException("unable to attach metadata cache for player " + player);
        }
        ZipFile oldCache;

        // Open and validate the cache
        ZipFile newCache = new ZipFile(cache, ZipFile.OPEN_READ);
        ZipEntry zipEntry = newCache.getEntry(CACHE_FORMAT_ENTRY);
        InputStream is = newCache.getInputStream(zipEntry);
        Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
        String tag = null;
        if (s.hasNext()) tag = s.next();
        if (!CACHE_FORMAT_IDENTIFIER.equals(tag)) {
            try {
                newCache.close();
            } catch (Exception e) {
                logger.error("Problem re-closing newly opened candidate metadata cache", e);
            }
            throw new IOException("File does not contain a Beat Link metadata cache: " + cache +
            " (looking for format identifier \"" + CACHE_FORMAT_IDENTIFIER + "\", found: " + tag);
        }

        switch (slot) {
            case USB_SLOT:
                oldCache = usbMetadataCaches.put(player, newCache);
                break;

            case SD_SLOT:
                oldCache = sdMetadataCaches.put(player, newCache);
                break;

            default:
                try {
                    newCache.close();
                } catch (Exception e) {
                    logger.error("Problem re-closing newly opened candidate metadata cache", e);
                }
                throw new IllegalArgumentException("Cannot cache media for slot " + slot);
        }

        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing previous metadata cache", e);
            }
        }

        deliverCacheUpdate();
    }

    /**
     * Removes any metadata cache file that might have been assigned to a particular player media slot, so metadata
     * will be looked up from the player itself.
     *
     * @param player the player number for which a metadata cache is to be attached
     * @param slot the media slot to which a meta data cache is to be attached
     */
    @SuppressWarnings("WeakerAccess")
    public static void detachMetadataCache(int player, CdjStatus.TrackSourceSlot slot) {
        ZipFile oldCache = null;
        switch (slot) {
            case USB_SLOT:
                oldCache = usbMetadataCaches.remove(player);
                break;

            case SD_SLOT:
                oldCache = sdMetadataCaches.remove(player);
                break;

            default:
                logger.warn("Ignoring request to remove metadata cache for slot {}", slot);
        }

        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing metadata cache", e);
            }
            deliverCacheUpdate();
        }
    }

    /**
     * Finds the metadata cache file assigned to a particular player media slot, if any.
     *
     * @param player the player number for which a metadata cache is to be attached
     * @param slot the media slot to which a meta data cache is to be attached
     *
     * @return the zip file being used as a metadata cache for that player and slot, or {@code null} if no cache
     *         has been attached
     */
    @SuppressWarnings("WeakerAccess")
    public static ZipFile getMetadataCache(int player, CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                return usbMetadataCaches.get(player);
            case SD_SLOT:
                return  sdMetadataCaches.get(player);
            default:
                return null;
        }
    }

    /**
     * Keeps track of any players with mounted SD media.
     */
    private static final Set<Integer> sdMounts = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of any players with mounted USB media.
     */
    private static final Set<Integer> usbMounts = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Records that there is media mounted in a particular media player slot, updating listeners if this is a change.
     *
     * @param player the number of the player that has media in the specified slot
     * @param slot the slot in which media is mounted
     */
    private static void recordMount(int player, CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                if (usbMounts.add(player)) {
                    deliverCacheUpdate();
                }
                break;
            case SD_SLOT:
                if (sdMounts.add(player)) {
                    deliverCacheUpdate();
                }
                break;
            default:
                throw new IllegalArgumentException("Cannot record mounted media in slot " + slot);
        }
    }

    /**
     * Records that there is no media mounted in a particular media player slot, updating listeners if this is a change.
     *
     * @param player the number of the player that has no media in the specified slot
     * @param slot the slot in which no media is mounted
     */
    private static void removeMount(int player, CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                if (usbMounts.remove(player)) {
                    deliverCacheUpdate();
                }
                break;
            case SD_SLOT:
                if (sdMounts.remove(player)) {
                    deliverCacheUpdate();
                }
                break;
            default:
                logger.warn("Ignoring request to record unmounted media in slot {}", slot);
        }
    }

    /**
     * Returns the set of player numbers that currently have media mounted in the specified slot.
     *
     * @param slot the slot of interest, currently must be either {@code SD_SLOT} or {@code USB_SLOT}
     *
     * @return the player numbers with media currently mounted in the specified slot
     */
    public static Set<Integer> getPlayersWithMediaIn(CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                return Collections.unmodifiableSet(usbMounts);
            case SD_SLOT:
                return Collections.unmodifiableSet(sdMounts);
            default:
                throw new IllegalArgumentException("Cannot report mounted media in slot " + slot);
        }
    }

    /**
     * Keeps track of the registered cache update listeners.
     */
    private static final Set<MetadataCacheUpdateListener> cacheListeners = new HashSet<MetadataCacheUpdateListener>();

    /**
     * Adds the specified cache update listener to receive updates when a metadata cache is attached or detached.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the cache update listener to add
     */
    public static synchronized void addCacheUpdateListener(MetadataCacheUpdateListener listener) {
        if (listener != null) {
            cacheListeners.add(listener);
        }
    }

    /**
     * Removes the specified cache update listener so that it no longer receives updates when there
     * are changes to the available set of metadata caches. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the master listener to remove
     */
    public static synchronized void removeCacheUpdateListener(MetadataCacheUpdateListener listener) {
        if (listener != null) {
            cacheListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized Set<MetadataCacheUpdateListener> getCacheUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<MetadataCacheUpdateListener>(cacheListeners));
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     */
    private static void deliverCacheUpdate() {
        final Map<Integer, ZipFile> sdCaches = Collections.unmodifiableMap(sdMetadataCaches);
        final Map<Integer, ZipFile> usbCaches = Collections.unmodifiableMap(usbMetadataCaches);
        final Set<Integer> sdSet = Collections.unmodifiableSet(sdMounts);
        final Set<Integer> usbSet = Collections.unmodifiableSet(usbMounts);
        for (final MetadataCacheUpdateListener listener : getCacheUpdateListeners()) {
            try {
                listener.cacheStateChanged(sdCaches, usbCaches, sdSet, usbSet);

            } catch (Exception e) {
                logger.warn("Problem delivering metadata cache update to listener", e);
            }
        }
    }

    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private static final Set<TrackMetadataUpdateListener> trackListeners = new HashSet<TrackMetadataUpdateListener>();

    /**
     * Adds the specified track metadata listener to receive updates when the track metadata for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the track metadata update listener to add
     */
    public static synchronized void addTrackMetadataUpdateListener(TrackMetadataUpdateListener listener) {
        if (listener != null) {
            trackListeners.add(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized Set<TrackMetadataUpdateListener> getTrackMetadataUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<TrackMetadataUpdateListener>(trackListeners));
    }

    /**
     * Removes the specified track metadata update listener so that it no longer receives updates when track
     * metadata for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the track metadata update listener to remove
     */
    public static synchronized void removeTrackMetadataUpdateListener(TrackMetadataUpdateListener listener) {
        if (listener != null) {
            trackListeners.remove(listener);
        }
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     */
    private static void deliverTrackMetadataUpdate(int player, TrackMetadata metadata) {
        for (final TrackMetadataUpdateListener listener : getTrackMetadataUpdateListeners()) {
            try {
                listener.metadataChanged(player, metadata);

            } catch (Exception e) {
                logger.warn("Problem delivering track metadata update to listener", e);
            }
        }
    }

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * Also clears out any metadata caches that were attached for slots that no longer have media mounted in them,
     * and updates the sets of which players have media mounted in which slots.
     *
     * If any of these reflect a change in state, any registered listeners will be informed.
     *
     * @param update an update packet we received from a CDJ
     */
    private static void handleUpdate(final CdjStatus update) {
        // First see if any metadata caches need evicting or mount sets need updating.
        if (update.isLocalUsbEmpty()) {
            detachMetadataCache(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
            removeMount(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
        } else if (update.isLocalUsbLoaded()) {
            recordMount(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
        }
        if (update.isLocalSdEmpty()) {
            detachMetadataCache(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
            removeMount(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
        } else if (update.isLocalSdLoaded()){
            recordMount(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
        }

        // Now see if a track has changed that needs new metadata.
        if (update.getTrackType() != CdjStatus.TrackType.REKORDBOX ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.UNKNOWN ||
                update.getRekordboxId() == 0) {  // We no longer have metadata for this device
            clearMetadata(update);
        } else {  // We can gather metadata for this device; check if we already looked up this track
            CdjStatus lastStatus = lastUpdates.get(update.address);
            if (lastStatus == null || lastStatus.getTrackSourceSlot() != update.getTrackSourceSlot() ||
                    lastStatus.getTrackSourcePlayer() != update.getTrackSourcePlayer() ||
                    lastStatus.getRekordboxId() != update.getRekordboxId()) {  // We have something new!
                if (activeRequests.add(update.getTrackSourcePlayer())) {
                    clearMetadata(update);  // We won't know what it is until our request completes.
                    // We had to make sure we were not already asking for this track.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                TrackMetadata data = requestMetadataInternal(update.getTrackSourcePlayer(),
                                        update.getTrackSourceSlot(), update.getRekordboxId(), true);
                                if (data != null) {
                                    updateMetadata(update, data);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting track metadata from update" + update, e);
                            } finally {
                                activeRequests.remove(update.getTrackSourcePlayer());
                            }
                        }
                    }).start();
                }
            }
        }
    }

    /**
     * Start finding track metadata for all active players. Starts the {@link VirtualCdj} if it is not already
     * running, because we need it to send us device status updates to notice when new tracks are loaded.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public static synchronized void start() throws Exception {
        if (!running) {
            ConnectionManager.start();
            DeviceFinder.start();
            DeviceFinder.addDeviceAnnouncementListener(announcementListener);
            VirtualCdj.start();
            VirtualCdj.addUpdateListener(updateListener);
            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            // Interrupted due to MetadataFinder shutdown, presumably
                        }
                    }
                }
            });
            running = true;
            queueHandler.start();
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    public static synchronized void stop() {
        if (running) {
            VirtualCdj.removeUpdateListener(updateListener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            lastUpdates.clear();
            metadata.clear();
        }
    }
}
