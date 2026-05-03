package me.apika.apikaprobe.worldgen.chunk;

import java.util.OptionalInt;

/**
 * Static holder for world-creation pre-gen state.
 *
 * <p>Two concerns, both client-process scoped:
 * <ul>
 *   <li><b>UI state</b> ({@link #isUiEnabled()}, {@link #getUiRadius()}):
 *       what the toggle + slider on the Create World "More" tab currently
 *       show. Persists across screen reopens within a single MC session
 *       so the user does not have to re-pick after backing out.
 *   <li><b>Pending request</b> ({@link #setRequest}, {@link #consumeRequestFor}):
 *       set on the create-world commit if the toggle was on; consumed
 *       once at {@code ServerLifecycleEvents.SERVER_STARTED} for the
 *       matching world name. Single-shot, world-name-keyed.
 * </ul>
 *
 * <p>Singleplayer-only: the dedicated server entry point is the
 * {@code ferrite.pregen.radius} property handled separately in Session 3.
 */
public final class PregenWorldOptions {
	private PregenWorldOptions() {}

	public static final int RADIUS_MIN = 5;
	public static final int RADIUS_MAX = 50;
	public static final int RADIUS_DEFAULT = 10;

	private static volatile boolean uiEnabled = false;
	private static volatile int uiRadius = RADIUS_DEFAULT;

	private static volatile String pendingWorldName = null;
	private static volatile int pendingRadius = 0;

	public static boolean isUiEnabled() { return uiEnabled; }
	public static void setUiEnabled(boolean v) { uiEnabled = v; }

	public static int getUiRadius() { return uiRadius; }
	public static void setUiRadius(int r) {
		uiRadius = Math.max(RADIUS_MIN, Math.min(RADIUS_MAX, r));
	}

	/** Stash a pending pre-gen for the world about to load. Called on
	 *  the create-world commit from the screen mixin. */
	public static synchronized void setRequest(String worldName, int radius) {
		pendingWorldName = worldName;
		pendingRadius = Math.max(RADIUS_MIN, Math.min(RADIUS_MAX, radius));
	}

	/** Single-shot consume. Returns the radius if a request was pending
	 *  for this world name; clears the slot in either case (a non-match
	 *  is not cleared, to avoid swallowing a request when the wrong
	 *  world's start fires first in some edge case). */
	public static synchronized OptionalInt consumeRequestFor(String worldName) {
		if (pendingWorldName == null) return OptionalInt.empty();
		if (!pendingWorldName.equals(worldName)) return OptionalInt.empty();
		int r = pendingRadius;
		pendingWorldName = null;
		pendingRadius = 0;
		return OptionalInt.of(r);
	}

	public static synchronized void clearRequest() {
		pendingWorldName = null;
		pendingRadius = 0;
	}
}
