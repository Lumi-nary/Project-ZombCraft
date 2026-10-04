package pzcraft.mc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * PZ (with Viewpoint's shaders) keeps the GPU close to saturated, and Windows time-slices the GPU between processes, so
 * Minecraft's small frames queue behind PZ's big ones and reach the overlay late. Ask WDDM to schedule this process's GPU
 * work ahead of normal-priority work; Minecraft's share of the GPU is tiny, so PZ does not notice.
 * Opt-in (set PZCRAFT_GPU_PRIORITY): HIGH was accepted but made no measurable difference to frame rate or latency.
 */
final class GpuPriority {
    private static final int ABOVE_NORMAL = 3, HIGH = 4;

    private GpuPriority() {}

    static void raise() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        if (System.getenv("PZCRAFT_GPU_PRIORITY") == null) return; // opt-in: measured no difference on the dev PC
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup gdi = SymbolLookup.libraryLookup("gdi32.dll", Arena.global());
            MethodHandle set = linker.downcallHandle(gdi.find("D3DKMTSetProcessSchedulingPriorityClass").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            long self = -1L; // GetCurrentProcess() pseudo handle
            int status = (int) set.invokeExact(self, HIGH);
            if (status != 0) {
                int fallback = (int) set.invokeExact(self, ABOVE_NORMAL);
                PzCraftClient.LOG.info("GPU priority: HIGH refused (status 0x{}), ABOVE_NORMAL status 0x{}", Integer.toHexString(status), Integer.toHexString(fallback));
            } else {
                PzCraftClient.LOG.info("GPU scheduling priority raised to HIGH");
            }
        } catch (Throwable t) {
            PzCraftClient.LOG.info("could not change GPU priority: {}", t.toString());
        }
    }
}

