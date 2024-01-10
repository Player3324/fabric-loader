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

import net.fabricmc.loader.api.extension.transform.ClassTransformApplicator;
import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;

final class ByteBufferApplicator implements ClassTransformApplicator<ByteBuffer, ByteBuffer> {
	@Override
	public ByteBuffer setup(ByteBuffer input) {
		return input;
	}

	@Override
	public ByteBuffer updateGenerate(ByteBuffer state, ClassTransformer<ByteBuffer> transformer, ClassTransformContext<ByteBuffer> context) {
		assert state == null;
		assert transformer.canGenerate();

		return transformer.generate(context);
	}

	@Override
	public ByteBuffer updateTransform(ByteBuffer state, ClassTransformer<ByteBuffer> transformer, ClassTransformContext<ByteBuffer> context) {
		assert state != null;
		assert transformer.canTransform();

		return transformer.transform(state, context).getOutput();
	}

	@Override
	public ByteBuffer finish(ByteBuffer input, ClassTransformContext<ByteBuffer> context) {
		return input;
	}

	@Override
	public boolean hasData(ByteBuffer state) {
		return state != null;
	}

	@Override
	public @Nullable ByteBuffer getData(ByteBuffer state) {
		return state;
	}
}
