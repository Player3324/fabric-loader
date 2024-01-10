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

package net.fabricmc.loader.impl.transformer;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformPhases;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.api.extension.transform.ClassTransformerBuilder;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.LoaderUtil;

final class MixinTransformer {
	public static ClassTransformer<?> create() {
		return ClassTransformerBuilder.create("mixin", ClassTransformHandler.FULL_CLASS_NODE_APPLICATOR)
				.setAnyTarget()
				.setPhase(ClassTransformPhases.MIXIN)
				.setTransformer(MixinTransformer::transform)
				.setGenerator(MixinTransformer::generate)
				.build();
	}

	private static TransformResult<ClassNode> transform(ClassNode input, ClassTransformContext<ClassNode> context) {
		boolean changed = getMixinTransformer().transformClass(MixinEnvironment.getCurrentEnvironment(),
				LoaderUtil.getDotName(context.getRuntimeClassName()),
				input);

		return TransformResult.create(input, changed);
	}

	private static ClassNode generate(ClassTransformContext<ClassNode> context) {
		ClassNode node = new ClassNode();

		if (getMixinTransformer().generateClass(MixinEnvironment.getCurrentEnvironment(),
				LoaderUtil.getDotName(context.getRuntimeClassName()),
				node)) {
			return node;
		} else {
			return null;
		}
	}

	private static IMixinTransformer getMixinTransformer() {
		return FabricLauncherBase.getLauncher().getMixinTransformer();
	}
}
