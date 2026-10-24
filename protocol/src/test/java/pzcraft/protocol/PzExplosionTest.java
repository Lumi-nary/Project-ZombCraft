package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PzExplosionTest {
    @Test
    void blastRoundTripsWithAllTargetKinds() {
        List<PzExplosion.Target> targets = List.of(
                new PzExplosion.Target(PzExplosion.KIND_EDGE_N, 2155, 6101, 0, Materials.edge(Materials.KIND_WALL, Materials.MAT_BRICK)),
                new PzExplosion.Target(PzExplosion.KIND_EDGE_W, -4, 9, 1, Materials.edge(Materials.KIND_WINDOW, Materials.MAT_GLASS)),
                new PzExplosion.Target(PzExplosion.KIND_SOLID, 10, 11, 0, Materials.MAT_METAL_HEAVY),
                new PzExplosion.Target(PzExplosion.KIND_FLOOR, 10, 11, 2, 0));
        PzExplosion.Blast blast = new PzExplosion.Blast(2158.5, 0.5, 6104.25, 4f, true, targets);
        PzExplosion.Blast back = PzExplosion.decode(PzExplosion.encode(blast));
        assertEquals(blast, back);
    }

    @Test
    void targetListIsCappedAndMalformedPayloadsAreRejected() {
        List<PzExplosion.Target> many = new ArrayList<>();
        for (int i = 0; i < PzExplosion.MAX_TARGETS + 50; i++) many.add(new PzExplosion.Target(PzExplosion.KIND_SOLID, i, 0, 0, 1));
        assertEquals(PzExplosion.MAX_TARGETS, PzExplosion.decode(PzExplosion.encode(new PzExplosion.Blast(0, 0, 0, 4f, false, many))).targets().size());
        byte[] good = PzExplosion.encode(new PzExplosion.Blast(0, 0, 0, 4f, false, many.subList(0, 3)));
        assertThrows(IllegalArgumentException.class, () -> PzExplosion.decode(java.util.Arrays.copyOf(good, good.length - 1)));
    }
}
