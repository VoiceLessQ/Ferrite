package me.apika.apikaprobe.hopper;

public final class PerSlotFireConfig {
	public static volatile boolean ENABLE   = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.perslot.enable", "false"));
	public static volatile boolean VALIDATE = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.perslot.validate", "false"));

	private PerSlotFireConfig() {}
}
