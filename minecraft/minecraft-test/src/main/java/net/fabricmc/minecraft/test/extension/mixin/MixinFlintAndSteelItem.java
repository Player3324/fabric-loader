package net.fabricmc.minecraft.test.extension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.fabricmc.loader.impl.util.log.Log;

import net.fabricmc.loader.impl.util.log.LogCategory;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@SuppressWarnings({"unused", "UnusedMixin"})
@Mixin(FlintAndSteelItem.class)
public class MixinFlintAndSteelItem {

	@WrapOperation(method = "useOnBlock", at = @At( value = "INVOKE", target = "Lnet/minecraft/block/AbstractFireBlock;getState(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;" ))
	private BlockState useOnBlock(BlockView world, BlockPos pos, Operation<BlockState> original, @Local(argsOnly = true) ItemUsageContext context) {
		ItemStack stack = context.getStack();
		if ((float)(stack.getMaxDamage()-stack.getDamage())/stack.getMaxDamage() >= 0.75f && context.getSide().equals(Direction.UP)) {
			return Blocks.SOUL_FIRE.getDefaultState();
		}
		return original.call(world, pos);
	}
}
