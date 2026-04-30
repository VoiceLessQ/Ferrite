package me.apika.apikaprobe.hopper;

public final class PerSlotFireConfig {
	public static final boolean ENABLE   = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.perslot.enable", "false"));
	public static final boolean VALIDATE = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.perslot.validate", "false"));

	private PerSlotFireConfig() {}
}
