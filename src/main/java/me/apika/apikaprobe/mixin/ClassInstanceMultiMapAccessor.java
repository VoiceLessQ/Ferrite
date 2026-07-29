package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.ClassInstanceMultiMap;

/** Exposes the backing list for indexed access by the section grid. */
@Mixin(ClassInstanceMultiMap.class)
public interface ClassInstanceMultiMapAccessor<T> {
	@Accessor("allInstances")
	List<T> ferrite$allInstances();
}
