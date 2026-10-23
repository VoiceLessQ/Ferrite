package me.apika.apikaprobe;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.fabricmc.loader.api.FabricLoader;

public class FerriteMixinPlugin implements IMixinConfigPlugin {

	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	// Moonrise replaces ThreadedLevelLightEngine internals; these monitor-only
	// mixins lose their injection targets there, so skip them (issue #12).
	private static final Set<String> LIGHT_MONITOR_MIXINS = Set.of(
			"me.apika.apikaprobe.mixin.ThreadedLevelLightEngineMixin",
			"me.apika.apikaprobe.mixin.LightTimingMixin");

	private boolean moonrise;
	private boolean logged;

	@Override
	public void onLoad(String mixinPackage) {
		this.moonrise = FabricLoader.getInstance().isModLoaded("moonrise");
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (moonrise && LIGHT_MONITOR_MIXINS.contains(mixinClassName)) {
			if (!logged) {
				LOGGER.info("[ferrite] Moonrise detected: light monitor mixins disabled ([light] shows no data)");
				logged = true;
			}
			return false;
		}
		return true;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	// Another mod mixing into vanilla's material rules turns the rule compiler's auto mode off; a property, so no game class loads here.
	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
		for (String target : otherTargets) {
			String name = target.replace('/', '.');
			if (name.startsWith("net.minecraft.world.level.levelgen.material.")) {
				System.setProperty("ferrite.rules.yield", name);
				LOGGER.info("[ferrite] another mod mixes into {}: rule compiler off (-Dferrite.rules=on overrides)", name);
				return;
			}
		}
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
