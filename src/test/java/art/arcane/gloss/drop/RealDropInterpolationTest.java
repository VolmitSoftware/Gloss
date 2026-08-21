package art.arcane.gloss.drop;

import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealDropInterpolationTest {
    @Test
    void changedPoseRearmsClientInterpolationBeforeSendingTheTransformation() {
        List<String> calls = new ArrayList<>();
        ItemDisplay display = recordingDisplay(calls);

        RealDropService.applyInterpolatedTransformation(display, transformation(), 4);

        assertEquals(List.of(
            "setInterpolationDuration:4",
            "setInterpolationDelay:-1",
            "setTransformation"), calls);
    }

    @Test
    void immediatePoseDoesNotArmInterpolation() {
        List<String> calls = new ArrayList<>();
        ItemDisplay display = recordingDisplay(calls);

        RealDropService.applyInterpolatedTransformation(display, transformation(), 0);

        assertEquals(List.of(
            "setInterpolationDuration:0",
            "setTransformation"), calls);
    }

    private static ItemDisplay recordingDisplay(List<String> calls) {
        return (ItemDisplay) Proxy.newProxyInstance(
            RealDropInterpolationTest.class.getClassLoader(),
            new Class<?>[]{ItemDisplay.class},
            (proxy, method, arguments) -> {
                if ("setInterpolationDuration".equals(method.getName())) {
                    calls.add(method.getName() + ":" + arguments[0]);
                } else if ("setInterpolationDelay".equals(method.getName())) {
                    calls.add(method.getName() + ":" + arguments[0]);
                } else if ("setTransformation".equals(method.getName())) {
                    calls.add(method.getName());
                }
                return null;
            });
    }

    private static Transformation transformation() {
        return new Transformation(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(1.0F, 1.0F, 1.0F),
            new Quaternionf());
    }
}
