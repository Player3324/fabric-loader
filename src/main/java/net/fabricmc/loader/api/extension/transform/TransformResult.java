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

package net.fabricmc.loader.api.extension.transform;

import net.fabricmc.loader.impl.transformer.TransformResultImpl;

public interface TransformResult<T> {
	static <T> TransformResult<T> changed(T result) {
		return create(result, true);
	}

	static <T> TransformResult<T> same(T input) {
		return create(input, false);
	}

	static <T> TransformResult<T> create(T result, boolean changed) {
		return new TransformResultImpl<>(result, changed);
	}

	T getOutput();
	boolean isChanged();
}
