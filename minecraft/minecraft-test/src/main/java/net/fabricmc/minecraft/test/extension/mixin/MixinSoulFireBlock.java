package net.fabricmc.minecraft.test.extension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.BlockState;
import net.minecraft.block.SoulFireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SoulFireBlock.class)
public class MixinSoulFireBlock {
	@WrapMethod(method = "canPlaceAt(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/WorldView;Lnet/minecraft/util/math/BlockPos;)Z")
	private boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos, Operation<Boolean> original) {
		return true;
	}
}
