package pzcraft.protocol;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActorGroundTest {
    @Test void undergroundCollisionFeedbackKeepsActorIdentityAndDesiredPosition() {
        var p=new ActorGround.Pose(17,2100.75,6000.5,2100.7,-14,6000.5);
        assertEquals(List.of(p),ActorGround.decode(ActorGround.encode(List.of(p))));
    }
    @Test void malformedOrUnboundedFeedbackIsRejected() {
        assertThrows(IllegalArgumentException.class,()->ActorGround.decode(new byte[5]));
        assertThrows(IllegalArgumentException.class,()->ActorGround.encode(List.of(new ActorGround.Pose(7,0,0,0,Double.NaN,0))));
        assertThrows(IllegalArgumentException.class,()->ActorGround.encode(List.of(new ActorGround.Pose(7,0,0,0,-49,0))));
        assertThrows(IllegalArgumentException.class,()->ActorGround.encode(List.of(new ActorGround.Pose(7,0,0,3,-1,0))));
    }
}
