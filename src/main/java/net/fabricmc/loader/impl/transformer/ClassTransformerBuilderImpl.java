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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.extension.transform.ClassTransformApplicator;
import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformTargetAnalyzerContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformerBuilder;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.loader.impl.transformer.ClassTransformerImpl.Target;

public final class ClassTransformerBuilderImpl<T> implements ClassTransformerBuilder<T> {
	private final String name;
	private final ClassTransformApplicator<T, ?> applicator;

	private final List<Target> targets = new ArrayList<>();
	private Predicate<String> runtimeNamePredicate;
	private Function<ClassTransformTargetAnalyzerContext<T>, Collection<String>> targetAnalyzer;
	private boolean isAnyTarget;

	private String phase;
	private BiFunction<T, ClassTransformContext<T>, TransformResult<T>> transformer;
	private Function<ClassTransformContext<T>, T> generator;

	public ClassTransformerBuilderImpl(String name, ClassTransformApplicator<T, ?> applicator) {
		Objects.requireNonNull(name, "null name");
		if (name.isEmpty()) throw new IllegalArgumentException("empty name");
		Objects.requireNonNull(applicator, "null applicator");

		this.name = name;
		this.applicator = applicator;
	}

	@Override
	public ClassTransformerBuilder<T> addTarget(String name, String namespace, boolean optional) {
		Objects.requireNonNull(namespace, "null namespace");
		if (namespace.isEmpty()) throw new IllegalArgumentException("empty namespace");

		return addTarget0(name, namespace, optional);
	}

	@Override
	public ClassTransformerBuilder<T> addRuntimeNameTarget(String runtimeName, boolean optional) {
		return addTarget0(runtimeName, null, optional);
	}

	private ClassTransformerBuilder<T> addTarget0(String name, @Nullable String namespace, boolean optional) {
		Objects.requireNonNull(name, "null name");
		if (name.isEmpty()) throw new IllegalArgumentException("empty name");
		if (name.indexOf('.') >= 0) throw new IllegalArgumentException("name needs to be in slash form (some/pkg/cls): "+name);
		if (isAnyTarget) throw new IllegalStateException("already using wildcard target");

		for (Target target : targets) {
			// FIXME: this can't effectively compare null and the actual name for the runtime namespace
			if (target.name.equals(name) && Objects.equals(target.namespace, namespace)) throw new IllegalStateException("target "+name+" in namespace "+namespace+" was already added");
		}

		targets.add(new Target(name, namespace, optional));

		return this;
	}

	@Override
	public ClassTransformerBuilder<T> setDynamicTarget(Predicate<String> runtimeNamePredicate) {
		Objects.requireNonNull(runtimeNamePredicate, "null runtimeNamePredicate");
		if (isAnyTarget) throw new IllegalStateException("already using wildcard target");

		this.runtimeNamePredicate = runtimeNamePredicate;

		return this;
	}

	@Override
	public ClassTransformerBuilder<T> setTargetAnalyzer(Function<ClassTransformTargetAnalyzerContext<T>, Collection<String>> targetAnalyzer) {
		Objects.requireNonNull(targetAnalyzer, "null targetAnalyzer");
		if (isAnyTarget) throw new IllegalStateException("already using wildcard target");

		this.targetAnalyzer = targetAnalyzer;

		return this;
	}

	@Override
	public ClassTransformerBuilder<T> setAnyTarget() {
		if (!targets.isEmpty() || runtimeNamePredicate != null || targetAnalyzer != null) throw new IllegalStateException("already using specific target types");

		isAnyTarget = true;

		return this;
	}

	@Override
	public ClassTransformerBuilder<T> setPhase(String phase) {
		this.phase = phase;

		return this;
	}

	@Override
	public ClassTransformerBuilder<T> setTransformer(BiFunction<T, ClassTransformContext<T>, TransformResult<T>> impl) {
		Objects.requireNonNull(impl, "null impl");
		if (transformer != null) throw new IllegalStateException("transformer already set");

		this.transformer = impl;

		return this;
	}

	@Override
	public ClassTransformerBuilder<T> setGenerator(Function<ClassTransformContext<T>, T> impl) {
		Objects.requireNonNull(impl, "null impl");
		if (generator != null) throw new IllegalStateException("generator already set");

		this.generator = impl;

		return this;
	}

	@Override
	public ClassTransformerImpl<T> build() {
		if (targets.isEmpty() && runtimeNamePredicate == null && targetAnalyzer == null && !isAnyTarget) throw new IllegalStateException("no target set");
		if (transformer == null && generator == null) throw new IllegalStateException("neither transformer nor generator set");

		return new ClassTransformerImpl<>(name,
				applicator,
				targets, runtimeNamePredicate, targetAnalyzer, isAnyTarget,
				phase,
				transformer, generator);
	}
}
