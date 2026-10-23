package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.worldgen.RuleCompiler;

import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

// Hands buildSurface's once-per-chunk rule compile to RuleCompiler; priority 1100 and require 0 yield to an earlier redirect.
@Mixin(value = MaterialSystem.class, priority = 1100)
public abstract class MaterialSystemRuleCompilerMixin {

	@Redirect(method = "buildSurface", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;compile(Lnet/minecraft/world/level/levelgen/material/MaterialRuleContext;)Lnet/minecraft/world/level/levelgen/material/rule/RuleEvaluator;"))
	private RuleEvaluator ferrite$compileRules(MaterialRule rule, MaterialRuleContext context) {
		return RuleCompiler.compile(rule, context);
	}
}
