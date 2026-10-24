package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class InputReaderTest {
    @Test
    void aRestartCannotReplayThePreviousProcessesPoseOrInput() throws Exception {
        var path = Files.createTempFile("pzcraft-restart-input", ".shm");
        try (var pz = SharedLink.open(SharedLink.Role.PZ, path);
             var mc = SharedLink.open(SharedLink.Role.MC, path)) {
            var in = new InputState(); in.frame = 1; in.active = true; in.buttons = InputState.FWD;
            pz.writeInputState(in);
            var reader = new InputReader();
            assertTrue(reader.read(mc, true).active);
            pz.nativeWorldRendered(true); assertTrue(mc.nativeWorldActive());
            mc.writePlayerState(new PlayerState());
            try (var restarted = SharedLink.open(SharedLink.Role.PZ, path)) {
                assertNull(reader.read(mc, true), "restarting the input writer clears its old slot");
                assertFalse(mc.nativeWorldActive(), "restarting PZ clears the native-render acknowledgment");
                in.buttons = InputState.JUMP;
                restarted.writeInputState(in);
                assertEquals(InputState.JUMP, reader.read(mc, true).buttons, "a reused frame id is fresh after reset");
            }
            try (var restarted = SharedLink.open(SharedLink.Role.MC, path)) {
                assertFalse(pz.readPlayerState(new PlayerState()), "restarting Steve clears the old pose");
            }
        }
    }

    @Test
    void readsNewInputAtUseAndKeepsPreviousSnapshotsStable() throws Exception {
        var path = Files.createTempFile("pzcraft-direct-input", ".shm");
        try (var pz = SharedLink.open(SharedLink.Role.PZ, path);
             var mc = SharedLink.open(SharedLink.Role.MC, path)) {
            var reader = new InputReader();
            assertNull(reader.read(mc, true));
            var in = new InputState();
            in.frame = 1; in.active = true; in.buttons = InputState.FWD;
            in.yawDeg = 40; in.eyeHeight = 1.7f; in.fovDeg = 65; in.aspect = 1.8f;
            pz.writeInputState(in);
            var first = reader.read(mc, true);
            assertEquals(InputState.FWD, first.buttons);
            assertSame(first, reader.read(mc, true));
            in.frame = 2; in.buttons = InputState.JUMP; in.yawDeg = 90;
            pz.writeInputState(in);
            var second = reader.read(mc, true);
            assertEquals(2, second.frame);
            assertEquals(InputState.JUMP, second.buttons);
            assertEquals(90, second.yawDeg);
            assertEquals(1.7f, second.eyeHeight);
            assertEquals(65, second.fovDeg);
            assertEquals(1.8f, second.aspect);
            assertEquals(40, first.yawDeg);
            assertNull(reader.read(mc, false));
        }
    }
}
