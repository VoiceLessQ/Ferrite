package me.apika.apikaprobe.hopper;

public final class HopperLaneRouteConfig {
	public static volatile boolean ENABLE = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.lane.enable", "false"));

	private HopperLaneRouteConfig() {}
}
