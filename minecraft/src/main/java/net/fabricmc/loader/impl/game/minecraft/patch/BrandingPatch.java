/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.game.minecraft.patch;

import java.util.ListIterator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformPhases;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.api.extension.transform.ClassTransformerBuilder;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.loader.impl.game.minecraft.Hooks;
import net.fabricmc.loader.impl.transformer.ClassTransformHandler;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class BrandingPatch {
	public static ClassTransformer<?> create() {
		return ClassTransformerBuilder.create("branding", ClassTransformHandler.FULL_CLASS_NODE_APPLICATOR)
				.addRuntimeNameTarget("net/minecraft/client/ClientBrandRetriever", true)
				.addRuntimeNameTarget("net/minecraft/server/MinecraftServer", true)
				.setPhase(ClassTransformPhases.BEFORE_MIXIN)
				.setTransformer(BrandingPatch::transform)
				.build();
	}

	private static TransformResult<ClassNode> transform(ClassNode classNode, ClassTransformContext<ClassNode> context) {
		boolean applied = false;

		for (MethodNode node : classNode.methods) {
			if (node.name.equals("getClientModName") || node.name.equals("getServerModName") && node.desc.endsWith(")Ljava/lang/String;")) {
				Log.debug(LogCategory.GAME_PATCH, "Applying brand name hook to %s::%s", classNode.name, node.name);

				ListIterator<AbstractInsnNode> it = node.instructions.iterator();

				while (it.hasNext()) {
					if (it.next().getOpcode() == Opcodes.ARETURN) {
						it.previous();
						it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Hooks.INTERNAL_NAME, "insertBranding", "(Ljava/lang/String;)Ljava/lang/String;", false));
						it.next();
					}
				}

				applied = true;
			}
		}

		return TransformResult.create(classNode, applied);
	}
}
