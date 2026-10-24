package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class SceneLinkTest {
    @Test
    void animatedAtlasAndMeshCanReusePrivateUploadStorage() throws Exception {
        var path=Files.createTempFile("pzcraft-reuse-scene", ".shm");
        try(var writer=SceneLink.open(true,path);var reader=SceneLink.open(false,path)) {
            var pixels=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            var vertices=ByteBuffer.allocate(3*SceneLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer atlasStorage=null,meshStorage=null;long ar=0,mr=0;
            for(int n=0;n<1000;n++) {
                pixels.putInt(0,n);vertices.putFloat(0,n);
                long next=writer.writeAtlas(4,4,pixels);writer.writeMesh(next,0,0,0,vertices);
                var atlas=reader.readAtlas(ar,atlasStorage);var mesh=reader.readMesh(mr,meshStorage);
                assertNotNull(atlas);assertNotNull(mesh);
                if(atlasStorage!=null)assertSame(atlasStorage,atlas.pixels());
                if(meshStorage!=null)assertSame(meshStorage,mesh.vertices());
                atlasStorage=atlas.pixels();meshStorage=mesh.vertices();ar=atlas.revision();mr=mesh.revision();
                assertEquals(n,atlasStorage.getInt(0));assertEquals(n,meshStorage.getFloat(0));
            }
        }
    }
    @Test
    void atlasAndTrianglesRemainStableAcrossUpdatesAndEmptyMeshRemovesBlocks() throws Exception {
        var path = Files.createTempFile("pzcraft-scene", ".shm");
        try (var writer = SceneLink.open(true, path); var reader = SceneLink.open(false, path)) {
            assertFalse(reader.available());
            assertNull(reader.readAtlas(0)); assertNull(reader.readMesh(0));
            assertNull(reader.readSelection());
            writer.writeSelection(true, 10770, 0, 9474, 0, 0, 0, 1, .5f, 1);
            var selected = reader.readSelection();
            assertTrue(selected.active()); assertEquals(10770, selected.x()); assertEquals(.5f, selected.maxY());
            writer.writeSelection(false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            assertFalse(reader.readSelection().active());
            var pixels = ByteBuffer.allocate(4 * 4 * 4);
            for (int i = 0; i < pixels.capacity(); i++) pixels.put((byte)i);
            pixels.flip();
            long atlasRevision = writer.writeAtlas(4, 4, pixels);
            var triangles = ByteBuffer.allocate(3 * SceneLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 36; i++) triangles.putFloat(i + .5f);
            triangles.flip();
            long revision = writer.writeMesh(atlasRevision, 10768, -5, 9472, triangles);
            assertTrue(reader.available());
            var atlas = reader.readAtlas(0); var mesh = reader.readMesh(0);
            assertEquals(atlasRevision, atlas.revision());
            assertEquals(4, atlas.width()); assertEquals(63, atlas.pixels().get(63));
            assertEquals(revision, mesh.revision()); assertEquals(atlas.revision(), mesh.atlasRevision());
            assertEquals(10768, mesh.x()); assertEquals(-5, mesh.y()); assertEquals(9472, mesh.z());
            assertEquals(3, mesh.vertexCount()); assertEquals(35.5f, mesh.vertices().getFloat(35 * 4));
            assertNull(reader.readMesh(revision)); assertNull(reader.readAtlas(atlasRevision));
            writer.writeMesh(atlasRevision, 0, 0, 0, ByteBuffer.allocate(0));
            assertEquals(0, reader.readMesh(revision).vertexCount());
            assertEquals(35.5f, mesh.vertices().getFloat(35 * 4), "an old private snapshot is unchanged");
            writer.invalidate(); assertFalse(reader.available());
            assertThrows(IllegalStateException.class, () -> reader.writeAtlas(4, 4, pixels));
            assertThrows(IllegalArgumentException.class, () -> writer.writeAtlas(0, 4, pixels));
            assertThrows(IllegalArgumentException.class, () -> writer.writeMesh(atlasRevision, 0, 0, 0, ByteBuffer.allocate(48)));
        }
        try (var restarted = SceneLink.open(true, path); var reader = SceneLink.open(false, path)) {
            assertFalse(reader.available()); assertNull(reader.readAtlas(0)); assertNull(reader.readMesh(0));
        }
    }

    @Test
    void readerNeverAcceptsMixedTrianglesDuringConcurrentPublication() throws Exception {
        var path = Files.createTempFile("pzcraft-scene-race", ".shm");
        try (var writer = SceneLink.open(true, path); var reader = SceneLink.open(false, path)) {
            long atlas = writer.writeAtlas(1, 1, ByteBuffer.allocate(4));
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var publisher = new Thread(() -> {
                try {
                    var payload = ByteBuffer.allocate(300 * SceneLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                    for (int n = 1; n <= 200; n++) {
                        payload.clear();
                        while (payload.hasRemaining()) payload.putFloat(n);
                        payload.flip(); writer.writeMesh(atlas, n, n, n, payload);
                    }
                } catch (Throwable t) { error.set(t); }
            });
            publisher.start();
            do {
                var mesh = reader.readMesh(0);
                if (mesh != null) {
                    assertEquals(mesh.x(), mesh.y()); assertEquals(mesh.y(), mesh.z());
                    while (mesh.vertices().hasRemaining()) assertEquals((float)mesh.x(), mesh.vertices().getFloat());
                }
            } while (publisher.isAlive());
            publisher.join(); assertNull(error.get());
        }
    }
}
