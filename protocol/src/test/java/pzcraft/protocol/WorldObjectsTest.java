package pzcraft.protocol;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorldObjectsTest {
    @Test void snapshotsRemoveStaleLightsAndRidersAndPreserveIdentity(){
        var faces=List.of(new WorldObjects.ObjectFace(9,10700,9800,2,WorldObjects.ObjectFace.NORTH|WorldObjects.ObjectFace.OPEN));
        assertEquals(faces,WorldObjects.faces(WorldObjects.faces(faces)));
        var lights=List.of(new WorldObjects.Light(10700,5,9800,14,.5f,.2f,.1f));
        assertEquals(lights,WorldObjects.lights(WorldObjects.lights(lights)));
        var riders=List.of(new Passengers.Rider(9,11,10700.125,5.5,9800.25,80));
        assertEquals(riders,Passengers.decode(Passengers.encode(riders)));
        assertEquals(List.of(),Passengers.decode(Passengers.encode(List.of())));
        assertEquals(List.of(),WorldObjects.lights(WorldObjects.lights(List.of())));
    }
    @Test void malformedSnapshotsCannotCreateGhostObjects(){
        assertThrows(IllegalArgumentException.class,()->WorldObjects.faces(new byte[]{-1,-1,-1,-1}));
        assertThrows(IllegalArgumentException.class,()->WorldObjects.lights(new byte[]{1,0,0,0}));
        assertThrows(IllegalArgumentException.class,()->Passengers.decode(new byte[]{1,0,0,0}));
    }
}
