package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.ChunkSaveMonitor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {

	@Inject(method = "copyOf", at = @At("HEAD"))
	private static void ferrite$copyOfStart(
		ServerLevel level, ChunkAccess chunk,
		CallbackInfoReturnable<SerializableChunkData> cir
	) {
		ChunkSaveMonitor.onCopyStart();
	}

	@Inject(method = "copyOf", at = @At("RETURN"))
	private static void ferrite$copyOfEnd(
		ServerLevel level, ChunkAccess chunk,
		CallbackInfoReturnable<SerializableChunkData> cir
	) {
		ChunkSaveMonitor.onCopyEnd();
	}

	@Inject(method = "write", at = @At("HEAD"))
	private void ferrite$writeStart(CallbackInfoReturnable<CompoundTag> cir) {
		ChunkSaveMonitor.onWriteStart();
	}

	@Inject(method = "write", at = @At("RETURN"))
	private void ferrite$writeEnd(CallbackInfoReturnable<CompoundTag> cir) {
		ChunkSaveMonitor.onWriteEnd();
	}
}
