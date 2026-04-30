package me.apika.apikaprobe.hopper;

public interface SlotCooldownAccess {
	int ferrite$getSlotCooldown(int slot);
	void ferrite$setSlotCooldown(int slot, int value);
	int[] ferrite$getSlotCooldowns();

	int ferrite$getRoundRobinPointer();
	void ferrite$setRoundRobinPointer(int pointer);

	int ferrite$getLastInsertSlot();
	void ferrite$setLastInsertSlot(int slot);

	long[] ferrite$getLastFireTick();

	default void ferrite$decrementAllSlotCooldowns() {
		int[] cd = ferrite$getSlotCooldowns();
		for (int i = 0; i < cd.length; i++) cd[i]--;
	}

	default void ferrite$broadcastCooldown(int value) {
		int[] cd = ferrite$getSlotCooldowns();
		for (int i = 0; i < cd.length; i++) cd[i] = value;
	}

	default boolean ferrite$allSlotsOnCooldown() {
		for (int v : ferrite$getSlotCooldowns()) {
			if (v <= 0) return false;
		}
		return true;
	}

	default int ferrite$maxSlotCooldown() {
		int[] cd = ferrite$getSlotCooldowns();
		int max = cd[0];
		for (int i = 1; i < cd.length; i++) {
			if (cd[i] > max) max = cd[i];
		}
		return max;
	}
}
