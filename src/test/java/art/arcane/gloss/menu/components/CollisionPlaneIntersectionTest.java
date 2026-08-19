package art.arcane.gloss.menu.components;

import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.util.OptionalDouble;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins that the allocation-free {@code CollisionPlane.intersectionDistance} is bit-identical to the
 * vector form it replaced (plan item C3). The reference below is the previous implementation
 * verbatim; every assertion uses a zero delta, so a reordered floating-point expression fails.
 */
public class CollisionPlaneIntersectionTest {

  @Test
  public void matchesTheVectorFormExactlyAcrossRandomRays() {
    Random random = new Random(0xC0111DEL);
    int hits = 0;
    for (int trial = 0; trial < 2000; trial++) {
      CollisionPlane plane = new CollisionPlane(
          new Vector(random.nextDouble() * 20 - 10, 60 + random.nextDouble() * 10, random.nextDouble() * 20 - 10),
          0.5F + random.nextFloat() * 3F,
          0.5F + random.nextFloat() * 3F);
      plane.rotate(random.nextFloat() * 90F - 45F, random.nextFloat() * 360F, random.nextFloat() * 60F - 30F);
      Vector origin = new Vector(random.nextDouble() * 20 - 10, 60 + random.nextDouble() * 10,
          random.nextDouble() * 20 - 10);
      Vector direction = new Vector(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1,
          random.nextDouble() * 2 - 1).normalize();

      OptionalDouble actual = plane.intersectionDistance(origin.clone(), direction.clone());
      OptionalDouble expected = reference(plane, origin.clone(), direction.clone());

      assertEquals("trial " + trial + " presence", expected.isPresent(), actual.isPresent());
      if (expected.isPresent()) {
        hits++;
        assertEquals("trial " + trial + " distance",
            expected.getAsDouble(), actual.getAsDouble(), 0.0D);
      }
    }
    assertTrue("the sweep must actually hit the plane sometimes", hits > 0);
  }

  @Test
  public void aRayParallelToThePlaneMisses() {
    CollisionPlane plane = new CollisionPlane(new Vector(0, 65, 5), 2F, 2F);

    assertFalse(plane.intersectionDistance(new Vector(0, 65, 0), new Vector(1, 0, 0)).isPresent());
  }

  @Test
  public void aPlaneBehindTheRayMisses() {
    CollisionPlane plane = new CollisionPlane(new Vector(0, 65, -5), 2F, 2F);

    assertFalse(plane.intersectionDistance(new Vector(0, 65, 0), new Vector(0, 0, 1)).isPresent());
  }

  @Test
  public void theArgumentsAreNotMutated() {
    CollisionPlane plane = new CollisionPlane(new Vector(0, 65, 5), 2F, 2F);
    Vector origin = new Vector(0, 65, 0);
    Vector direction = new Vector(0, 0, 1);

    plane.intersectionDistance(origin, direction);

    assertEquals(new Vector(0, 65, 0), origin);
    assertEquals(new Vector(0, 0, 1), direction);
  }

  /** The pre-optimization body, kept verbatim as the equivalence oracle. */
  private static OptionalDouble reference(CollisionPlane plane, Vector origin, Vector direction) {
    Vector center = plane.getCenter();
    Vector normal = plane.getNormal();
    Vector offset = center.clone().subtract(origin);
    double proj = normal.dot(direction);
    if (Math.abs(proj) < 1.0E-9D) {
      return OptionalDouble.empty();
    }
    double distance = normal.dot(offset) / proj;
    if (distance < 0.0D) {
      return OptionalDouble.empty();
    }
    Vector intersect = origin.clone().add(direction.clone().multiply(distance)).subtract(center);
    float distX = (float) Math.abs(plane.getRight().dot(intersect));
    float distY = (float) Math.abs(plane.getUp().dot(intersect));
    return distX < plane.getWidth() / 2F && distY < plane.getHeight() / 2F
        ? OptionalDouble.of(distance)
        : OptionalDouble.empty();
  }
}
