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

import java.nio.ByteBuffer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformPhases;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.api.extension.transform.ClassTransformerBuilder;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.loader.impl.transformer.ClassTransformHandler;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Replace FML 1.2.5's ModClassLoader implementation with one that works on modern Java.
 */
public final class EntrypointPatchFML125 {
	private static final String TO = "cpw/mods/fml/common/ModClassLoader";

	public static ClassTransformer<?> create() {
		return ClassTransformerBuilder.create("entrypoint-fml-1.2.5", ClassTransformHandler.BYTE_BUFFER_APPLICATOR)
				.addRuntimeNameTarget(TO, true)
				.setPhase(ClassTransformPhases.PATCH)
				.setTransformer(EntrypointPatchFML125::transform)
				.build();
	}

	private static TransformResult<ByteBuffer> transform(ByteBuffer input, ClassTransformContext<ByteBuffer> context) {
		if (context.getClassBytes("cpw/mods/fml/relauncher/FMLRelauncher") != null) {
			return TransformResult.same(input);
		}

		Log.debug(LogCategory.GAME_PATCH, "Detected 1.2.5 FML - Knotifying ModClassLoader...");

		String from = LoaderUtil.getSlashName(ModClassLoader_125_FML.class.getName());

		// Load alternative implementation bundled with Fabric Loader for substitution
		ByteBuffer source = context.getClassBytes(LoaderUtil.getSlashName(ModClassLoader_125_FML.class.getName()));
		assert source != null;

		ClassReader reader = LoaderUtil.deserializeClass(source);
		ClassWriter writer = new ClassWriter(0);

		reader.accept(new ClassRemapper(writer, new Remapper() {
			@Override
			public String map(String internalName) {
				return from.equals(internalName) ? TO : internalName;
			}
		}), 0);

		return TransformResult.changed(ByteBuffer.wrap(writer.toByteArray()));
	}
}
