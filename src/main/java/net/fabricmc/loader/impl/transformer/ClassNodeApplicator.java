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

import java.nio.ByteBuffer;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loader.api.extension.transform.ClassTransformApplicator;
import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.loader.impl.transformer.ClassNodeApplicator.CnaState;
import net.fabricmc.loader.impl.util.LoaderUtil;

final class ClassNodeApplicator implements ClassTransformApplicator<ClassNode, CnaState> {
	private final int readFlags;
	private final int writeFlags;

	ClassNodeApplicator(int readFlags, int writeFlags) {
		this.readFlags = readFlags;
		this.writeFlags = writeFlags;
	}

	@Override
	public CnaState setup(ByteBuffer input) {
		ClassNode node;

		if (input != null) {
			ClassReader reader = LoaderUtil.deserializeClass(input);
			node = new ClassNode();
			reader.accept(node, readFlags);
		} else {
			node = null;
		}

		return new CnaState(input, node);
	}

	@Override
	public CnaState updateGenerate(CnaState state, ClassTransformer<ClassNode> transformer, ClassTransformContext<ClassNode> context) {
		assert state.node == null;
		assert transformer.canGenerate();

		ClassNode node = transformer.generate(context);

		if (node != null) {
			state.node = node;
			state.changed = true;
		}

		return state;
	}

	@Override
	public CnaState updateTransform(CnaState state, ClassTransformer<ClassNode> transformer, ClassTransformContext<ClassNode> context) {
		assert state.node != null;
		assert transformer.canTransform();

		TransformResult<ClassNode> result = transformer.transform(state.node, context);
		assert result.getOutput() == state.node;

		if (result.isChanged()) {
			state.changed = true;
		}

		return state;
	}

	@Override
	public ByteBuffer finish(CnaState state, ClassTransformContext<ClassNode> context) {
		if (!state.changed
				|| state.node == null) {
			return state.input;
		}

		ClassWriter writer;

		if ((writeFlags & ClassWriter.COMPUTE_FRAMES) == 0) {
			writer = new ClassWriter(writeFlags);
		} else {
			writer = new ContextAwareClassWriter(writeFlags, context);
		}

		state.node.accept(writer);

		return ByteBuffer.wrap(writer.toByteArray());
	}

	private static final class ContextAwareClassWriter extends ClassWriter {
		private final ClassTransformContext<ClassNode> context;

		ContextAwareClassWriter(int writeFlags, ClassTransformContext<ClassNode> context) {
			super(writeFlags);

			this.context = context;
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			return context.getCommonSuperClass(type1, type2);
		}
	}

	@Override
	public boolean hasData(CnaState state) {
		return state.node != null;
	}

	@Override
	public @Nullable ClassNode getData(CnaState state) {
		return state.node;
	}

	static final class CnaState {
		final ByteBuffer input;
		ClassNode node;
		boolean changed;

		CnaState(ByteBuffer input, ClassNode node) {
			this.input = input;
			this.node = node;
		}
	}
}
