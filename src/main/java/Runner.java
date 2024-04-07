import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class Runner {

    private static Logger LOG = LoggerFactory.getLogger(Runner.class);
    public static void main (String[] args) throws Exception{
        DeviceFinder.getInstance().start();
        System.out.println(DeviceFinder.getInstance().getCurrentDevices());
        VirtualCdj.getInstance().start();
//        VirtualRekordbox.getInstance().start();
       // CrateDigger.getInstance().start();

        DeviceFinder.getInstance().addDeviceAnnouncementListener(new DeviceAnnouncementStatusListener() {
            @Override
            public void deviceFound(DeviceAnnouncement announcement) {
                System.out.println(DeviceFinder.getInstance().getCurrentDevices());
                System.out.println(announcement);
                System.out.println("found");
            }

            @Override
            public void deviceLost(DeviceAnnouncement announcement) {

            }
        });





        UpdateSocketConnection.getInstance().addUpdateListener(new DeviceUpdateListener() {
           private Database database = new Database(new File("/Users/cprepos/Desktop/PIONEER/rekordbox/export.pdb"));

           @Override
            public void received(DeviceUpdate update) {
               if (update instanceof CdjStatus) {
                   int trackNumber = ((CdjStatus) update).getTrackNumber();
                   RekordboxPdb.TrackRow track = database.trackIndex.get((long)trackNumber);
                   System.out.println(update);
                   System.out.println(database.getText(track.title()));
                   System.out.println(track.artistId());
                   System.out.println(track.keyId());
                   System.out.println(trackNumber);
                   System.out.println(((CdjStatus) update).getTrackSourcePlayer());
                   System.out.println(((CdjStatus) update).isPlayingForwards());
                   System.out.println(((CdjStatus) update).isPlayingBackwards());
               }
            }

        });

        System.out.println("Started up!");
        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            System.out.println("Interrupted, exiting.");
        }
    }

}