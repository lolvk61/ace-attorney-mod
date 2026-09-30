package com.stratfat.aceattorney.court;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SiteTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	private static Site at(double x, double z) {
		return new Site(OVERWORLD, x, 64, z);
	}

	@Test
	void distanceIsEuclideanInThreeDimensions() {
		assertEquals(5.0, at(0, 0).distanceTo(new Site(OVERWORLD, 3, 64 + 4, 0)), 1e-9);
		assertEquals(0.0, at(7, 7).distanceTo(at(7, 7)), 1e-9);
	}

	@Test
	void exactlyTheRadiusCountsAsNear() {
		assertTrue(at(0, 0).isNear(at(50, 0)));
		assertTrue(at(0, 0).isNear(at(30, 40)));
		assertFalse(at(0, 0).isNear(at(50.001, 0)));
		assertFalse(at(0, 0).isNear(at(100, 0)));
	}

	@Test
	void differentDimensionsAreNeverNear() {
		Site overworld = at(0, 0);
		Site nether = new Site(NETHER, 0, 64, 0);
		assertEquals(Double.POSITIVE_INFINITY, overworld.distanceTo(nether));
		assertFalse(overworld.isNear(nether));
	}

	@Test
	void nearestPicksTheClosestWithinTheRadius() {
		Site far = at(200, 0);
		Site near = at(10, 0);
		Site nearer = at(3, 0);
		List<Site> sites = List.of(far, near, nearer);
		assertSame(nearer, at(0, 0).nearest(sites, s -> s));
		assertSame(far, at(190, 0).nearest(sites, s -> s));
	}

	@Test
	void nearestIsNullWhenNothingIsInRange() {
		assertNull(at(0, 0).nearest(List.of(at(51, 0), new Site(NETHER, 1, 64, 1)), s -> s));
		assertNull(at(0, 0).nearest(List.<Site>of(), s -> s));
	}
}
