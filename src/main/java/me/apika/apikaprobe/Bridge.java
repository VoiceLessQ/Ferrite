package me.apika.apikaprobe;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

class SerjioItem extends Item {
  public SerjioItem(Item.Settings settings) {
    super(settings);
  }

  @Override
  public native ActionResult use(World world, PlayerEntity user, Hand hand);
}
