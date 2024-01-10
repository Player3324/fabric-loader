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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.extension.transform.ClassTransformApplicator;
import net.fabricmc.loader.api.extension.transform.ClassTransformTargetAnalyzerContext;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

final class ClassTransformTargetAnalyzerContextImpl<T> implements ClassTransformTargetAnalyzerContext<T> {
	private final ClassTransformApplicator<T, ?> applicator;

	ClassTransformTargetAnalyzerContextImpl(ClassTransformApplicator<T, ?> applicator) {
		this.applicator = applicator;
	}

	@Override
	public boolean hasClass(String runtimeClassName) {
		return getClass(runtimeClassName) != null; // TODO: more efficient implementation that just checks existence
	}

	@Override
	public @Nullable T getClass(String runtimeClassName) {
		ByteBuffer bytes = getClassBytes(runtimeClassName);
		if (bytes == null) return null;

		return ClassTransformHandler.getData(bytes, applicator);
	}

	@Override
	public @Nullable ByteBuffer getClassBytes(String runtimeClassName) {
		if (runtimeClassName.indexOf('.') >= 0) throw new IllegalArgumentException("name needs to be in slash form (some/pkg/cls): "+runtimeClassName);

		byte[] bytes;

		try {
			bytes = FabricLauncherBase.getLauncher().getClassByteArray(runtimeClassName, false, true);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return bytes != null ? ByteBuffer.wrap(bytes) : null;
	}
}
