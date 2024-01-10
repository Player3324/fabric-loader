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

import java.util.Objects;

import net.fabricmc.loader.api.extension.transform.TransformResult;

public final class TransformResultImpl<T> implements TransformResult<T> {
	private final T output;
	private final boolean changed;

	public TransformResultImpl(T output, boolean changed) {
		Objects.requireNonNull(output, "null output");

		this.output = output;
		this.changed = changed;
	}

	@Override
	public T getOutput() {
		return output;
	}

	@Override
	public boolean isChanged() {
		return changed;
	}
}
