package com.stratfat.aceattorney.court;

import java.util.Collection;
import java.util.function.Function;

/**
 * Where a court session takes place: a dimension and a point in it. Two
 * sessions may not run closer than {@link #RADIUS} blocks to each other, and
 * players within that radius of a session are its audience.
 */
public record Site(String dimension, double x, double y, double z) {
	/** Minimum distance between two active sessions; also the audience radius of one. */
	public static final double RADIUS = 50.0;

	/** Distance in blocks, or infinity if the sites are in different dimensions. */
	public double distanceTo(Site other) {
		if (!dimension.equals(other.dimension)) {
			return Double.POSITIVE_INFINITY;
		}
		double dx = x - other.x;
		double dy = y - other.y;
		double dz = z - other.z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** True if the other site is within {@link #RADIUS} blocks (the boundary counts). */
	public boolean isNear(Site other) {
		return distanceTo(other) <= RADIUS;
	}

	/** The item closest to this site within {@link #RADIUS} blocks, or null if there is none. */
	public <T> T nearest(Collection<T> items, Function<T, Site> siteOf) {
		T best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (T item : items) {
			double distance = distanceTo(siteOf.apply(item));
			if (distance <= RADIUS && distance < bestDistance) {
				best = item;
				bestDistance = distance;
			}
		}
		return best;
	}
}
