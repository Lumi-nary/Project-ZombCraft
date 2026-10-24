package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityLinkTest {
    @Test
    void uploadStorageIsReusedAndIndependentOfMappedWriter() throws Exception {
        var path=Files.createTempFile("pzcraft-reuse-frame", ".shm");
        try(var writer=EntityLink.open(true,path);var reader=EntityLink.open(false,path)) {
            var vertices=ByteBuffer.allocate(3*EntityLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer storage=null;long revision=0;
            for(int n=0;n<1000;n++) {
                vertices.putFloat(0,n);writer.writeFrame(1,2,3,List.of(new EntityLink.Batch(0,0,3,0)),vertices);
                var frame=reader.readFrame(revision,storage);assertNotNull(frame);
                if(storage!=null)assertSame(storage,frame.vertices(),"steady geometry must not allocate direct storage per frame");
                storage=frame.vertices();revision=frame.revision();assertEquals(n,storage.getFloat(0));
                vertices.putFloat(0,-123);writer.writeFrame(1,2,3,List.of(new EntityLink.Batch(0,0,3,0)),vertices);
                assertEquals(n,storage.getFloat(0),"never expose a live shared-memory view");
            }
        }
    }
    @Test
    void catalogueItemAtlasFitsAlongsideExistingEntityAtlases() throws Exception {
        Path path = Files.createTempFile("pzcraft-item-atlas", ".shm");
        try (EntityLink mc = EntityLink.open(true, path); EntityLink pz = EntityLink.open(false, path)) {
            // Observed 26.3 set: player skin, equipment, particles, then the PZ item catalogue.
            ByteBuffer pixels = ByteBuffer.allocateDirect(4096 * 4096 * 4).order(ByteOrder.LITTLE_ENDIAN);
            mc.writeTexture(0, EntityLink.KIND_PIXELS, 64, 64, pixels.slice(0, 64 * 64 * 4));
            mc.writeTexture(2, EntityLink.KIND_PIXELS, 512, 512, pixels.slice(0, 512 * 512 * 4));
            mc.writeTexture(3, EntityLink.KIND_PIXELS, 2048, 1024, pixels.slice(0, 2048 * 1024 * 4));
            assertTrue(mc.textureBytesFree() >= pixels.capacity(), "held items must not lose their atlas after particles load");
            pixels.putInt(0, 0x12345678).putInt(pixels.capacity() - 4, 0x76543210);
            long revision = mc.writeTexture(4, EntityLink.KIND_PIXELS, 4096, 4096, pixels);
            EntityLink.Texture texture = pz.readTexture(4, 0);
            assertNotNull(texture);
            assertEquals(revision, texture.revision());
            assertEquals(4096, texture.width());
            assertEquals(4096, texture.height());
            assertEquals(0x12345678, texture.pixels().getInt(0));
            assertEquals(0x76543210, texture.pixels().getInt(pixels.capacity() - 4));
        }
    }

    @Test
    void pointBlankItemAtlasFitsWholeWithRoomForTheRest() {
        // Vic's Point Blank 2.2.0 makes the item atlas 8192x8192; it must go at full size with skins, particles etc. beside it.
        assertTrue(EntityLink.textureSpace() >= 2L * 8192 * 8192 * 4);
    }

    @Test
    void croppedTextureKeepsItsFullSize() throws Exception {
        Path path = Files.createTempFile("pzcraft-cropped", ".shm");
        try (EntityLink mc = EntityLink.open(true, path); EntityLink pz = EntityLink.open(false, path)) {
            // A 256x128 atlas whose used part is the top-left 40x24: rows of 256 pixels in, 40x24 out.
            ByteBuffer atlas = ByteBuffer.allocateDirect(256 * 128 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < 24; y++) for (int x = 0; x < 40; x++) atlas.putInt((y * 256 + x) * 4, y << 16 | x);
            int before = mc.textureBytesFree();
            long revision = mc.writeTexture(5, 40, 24, 256, 256, 128, atlas);
            assertEquals(40 * 24 * 4, before - mc.textureBytesFree(), "only the used part takes room");
            EntityLink.Texture t = pz.readTexture(5, 0);
            assertNotNull(t);
            assertEquals(revision, t.revision());
            assertEquals(EntityLink.KIND_PIXELS, t.kind());
            assertEquals(40, t.width());
            assertEquals(24, t.height());
            assertEquals(256, t.fullWidth());
            assertEquals(128, t.fullHeight());
            assertEquals(40 * 24 * 4, t.pixels().remaining());
            assertEquals(23 << 16 | 39, t.pixels().getInt((23 * 40 + 39) * 4));
            assertEquals(1 << 16 | 0, t.pixels().getInt(40 * 4));
            mc.writeTexture(6, EntityLink.KIND_PIXELS, 64, 64, ByteBuffer.allocateDirect(64 * 64 * 4));
            EntityLink.Texture whole = pz.readTexture(6, 0);
            assertEquals(64, whole.fullWidth());
            assertEquals(64, whole.fullHeight());
            assertThrows(IllegalArgumentException.class, () -> mc.writeTexture(7, 300, 24, 256, 256, 128, atlas));
        }
    }

    @Test void geometryCameraAndHandProjectionShareOneSnapshot() throws Exception {
        Path path=Files.createTempFile("pzcraft-pose",".shm");
        try(EntityLink mc=EntityLink.open(true,path);EntityLink pz=EntityLink.open(false,path)) {
            PlayerState pose=new PlayerState();pose.x=12000.125;pose.y=45;pose.z=6000.375;
            pose.cameraValid=true;pose.cameraMode=2;pose.cameraX=11996.125;pose.cameraY=46.62;pose.cameraZ=6000.375;
            pose.cameraYawDeg=90;pose.cameraPitchDeg=-15;pose.eyeHeight=1.62f;
            ByteBuffer v=ByteBuffer.allocateDirect(3*EntityLink.VERTEX_BYTES);
            int flags=EntityLink.FLAG_HAND|EntityLink.FLAG_NO_SHADOW|EntityLink.FLAG_TRANSLUCENT|EntityLink.FLAG_ADDITIVE;
            mc.writeFrame(12000,45,6000,List.of(new EntityLink.Batch(0,0,3,flags)),v,pose,81);
            var f=pz.readFrame(0);
            assertTrue(f.pose().cameraValid);assertEquals(2,f.pose().cameraMode);
            assertEquals(pose.cameraX,f.pose().cameraX);assertEquals(pose.cameraY,f.pose().cameraY);
            assertEquals(pose.cameraZ,f.pose().cameraZ);assertEquals(pose.cameraYawDeg,f.pose().cameraYawDeg);
            assertEquals(pose.cameraPitchDeg,f.pose().cameraPitchDeg);assertEquals(pose.x,f.pose().x);
            assertEquals(81,f.handFov());assertEquals(flags,f.batches().getFirst().flags());
        }
    }
    @Test
    void texturesAndFramesRoundTrip() throws Exception {
        Path p = Files.createTempFile("pzcraft-entities", ".shm");
        try (EntityLink mc = EntityLink.open(true, p); EntityLink pz = EntityLink.open(false, p)) {
            assertNull(pz.readFrame(0));
            assertNull(pz.readTexture(3, 0));

            ByteBuffer skin = ByteBuffer.allocateDirect(64 * 64 * 4);
            for (int i = 0; i < skin.capacity(); i++) skin.put(i, (byte) i);
            long rev = mc.writeTexture(3, EntityLink.KIND_PIXELS, 64, 64, skin);
            mc.writeTexture(0, EntityLink.KIND_BLOCK_ATLAS, 0, 0, null);
            EntityLink.Texture t = pz.readTexture(3, 0);
            assertEquals(rev, t.revision());
            assertEquals(64, t.width());
            assertEquals((byte) 77, t.pixels().get(77));
            assertNull(pz.readTexture(3, rev), "unchanged slot reads as no news");
            assertEquals(EntityLink.KIND_BLOCK_ATLAS, pz.readTexture(0, 0).kind());

            ByteBuffer v = ByteBuffer.allocateDirect(6 * EntityLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 6 * EntityLink.VERTEX_FLOATS; i++) v.putFloat(i);
            v.flip();
            long frameRev = mc.writeFrame(11000, 2, 9700, List.of(new EntityLink.Batch(3, 0, 3, EntityLink.FLAG_SHADOW_ONLY),
                    new EntityLink.Batch(0, 3, 3, EntityLink.FLAG_NO_SHADOW | EntityLink.FLAG_TRANSLUCENT)), v);
            EntityLink.Frame f = pz.readFrame(0);
            assertEquals(frameRev, f.revision());
            assertEquals(11000, f.x());
            assertEquals(9700, f.z());
            assertEquals(6, f.vertexCount());
            assertEquals(2, f.batches().size());
            assertEquals(EntityLink.FLAG_SHADOW_ONLY, f.batches().get(0).flags());
            assertEquals(EntityLink.FLAG_NO_SHADOW | EntityLink.FLAG_TRANSLUCENT, f.batches().get(1).flags());
            assertEquals(6 * EntityLink.VERTEX_BYTES, f.vertices().remaining(), "alpha travels with every vertex");
            assertEquals(13f, f.vertices().getFloat(13 * 4), 0);
            assertNull(pz.readFrame(frameRev));

            mc.resetTextures();
            EntityLink.Texture gone = pz.readTexture(3, rev);
            assertNotNull(gone);
            assertEquals(0, gone.revision(), "a reset slot tells the reader to drop its copy");
        }
    }
}
