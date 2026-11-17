package me.apika.apikaprobe.entity;

import java.util.function.BooleanSupplier;

final class CrammingTickState<T> {

	private volatile CompletedTick<T> completed;

	boolean runOnceOnServerThread(T world, long tick, boolean serverThread, BooleanSupplier batch) {
		if (!serverThread || isCompleted(world, tick) || !batch.getAsBoolean()) return false;
		completed = new CompletedTick<>(world, tick);
		return true;
	}

	boolean isCompleted(T world, long tick) {
		CompletedTick<T> snapshot = completed;
		return snapshot != null && snapshot.world() == world && snapshot.tick() == tick;
	}

	private record CompletedTick<T>(T world, long tick) {}
}
