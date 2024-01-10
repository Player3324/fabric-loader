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

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.loader.impl.transformer.ClassTransformerBuilderImpl;

public interface ClassTransformerBuilder<T> {
	static <T> ClassTransformerBuilder<T> create(String name, ClassTransformApplicator<T, ?> applicator) {
		return new ClassTransformerBuilderImpl<>(name, applicator);
	}

	ClassTransformerBuilder<T> addTarget(String name, String namespace, boolean optional);
	ClassTransformerBuilder<T> addRuntimeNameTarget(String runtimeName, boolean optional);
	ClassTransformerBuilder<T> setDynamicTarget(Predicate<String> runtimeNamePredicate);
	ClassTransformerBuilder<T> setTargetAnalyzer(Function<ClassTransformTargetAnalyzerContext<T>, Collection<String>> targetAnalyzer);
	ClassTransformerBuilder<T> setAnyTarget();

	ClassTransformerBuilder<T> setPhase(String phase);

	ClassTransformerBuilder<T> setTransformer(BiFunction<T, ClassTransformContext<T>, TransformResult<T>> impl);
	ClassTransformerBuilder<T> setGenerator(Function<ClassTransformContext<T>, T> impl);

	ClassTransformer<T> build();
}
