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

import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.extension.transform.ClassTransformApplicator;
import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformTargetAnalyzerContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.mappingio.tree.MappingTreeView;

public final class ClassTransformerImpl<T> implements ClassTransformer<T> {
	final String name;
	final ClassTransformApplicator<T, ?> applicator;

	final List<Target> targets;
	final Predicate<String> runtimeNamePredicate;
	final Function<ClassTransformTargetAnalyzerContext<T>, Collection<String>> targetAnalyzer;
	final boolean isAnyTarget;

	final String phase;
	final BiFunction<T, ClassTransformContext<T>, TransformResult<T>> transformer;
	final Function<ClassTransformContext<T>, T> generator;

	String extensionModId;

	ClassTransformerImpl(String name,
			ClassTransformApplicator<T, ?> applicator,
			List<Target> targets, Predicate<String> runtimeNamePredicate,
			Function<ClassTransformTargetAnalyzerContext<T>, Collection<String>> targetAnalyzer,
			boolean isAnyTarget,
			String phase,
			BiFunction<T, ClassTransformContext<T>, TransformResult<T>> transformer,
			Function<ClassTransformContext<T>, T> generator) {
		this.name = name;
		this.applicator = applicator;
		this.targets = targets;
		this.runtimeNamePredicate = runtimeNamePredicate;
		this.targetAnalyzer = targetAnalyzer;
		this.isAnyTarget = isAnyTarget;
		this.phase = phase;
		this.transformer = transformer;
		this.generator = generator;
	}

	@Override
	public boolean canGenerate() {
		return generator != null;
	}

	@Override
	public boolean canTransform() {
		return transformer != null;
	}

	@Override
	public @Nullable T generate(ClassTransformContext<T> context) {
		return generator.apply(context);
	}

	@Override
	public TransformResult<T> transform(T input, ClassTransformContext<T> context) {
		return transformer.apply(input, context);
	}

	boolean appliesTo(String runtimeClassName) {
		if (isAnyTarget) return true;
		if (runtimeNamePredicate != null && runtimeNamePredicate.test(runtimeClassName)) return true;

		for (Target target : targets) {
			if (target.matches(runtimeClassName)) return true;
		}

		return false;
	}

	@Override
	public String toString() {
		return String.format("%s:%s", extensionModId, name);
	}

	static final class Target {
		final String name;
		final @Nullable String namespace; // null always means runtime namespace
		final boolean optional;
		@Nullable String runtimeClassName;

		Target(String name, @Nullable String namespace, boolean optional) {
			this.name = name;
			this.namespace = namespace;
			this.optional = optional;

			if (namespace == null) runtimeClassName = name;
		}

		boolean matches(String runtimeClassName) {
			return this.runtimeClassName.equals(runtimeClassName);
		}

		/**
		 * Simple runtimeClassName init without accessing mappings, if possible.
		 *
		 * @return whether the name was initialized without needing mappings
		 */
		boolean initRuntimeNameNoop(String runtimeNamespace) {
			if (runtimeClassName != null) return true;
			if (!namespace.equals(runtimeNamespace)) return false;

			this.runtimeClassName = name;

			return true;
		}

		/**
		 * Set runtimeClassName with mappings potentially available (errors if missing and non-optional).
		 */
		void initRuntimeName(String runtimeNamespace, @Nullable MappingTreeView tree, int runtimeNamespaceId) {
			if (runtimeClassName != null) return;

			if (namespace.equals(runtimeNamespace)) {
				this.runtimeClassName = name;
				return;
			}

			if (tree == null) { // no mappings
				if (!optional) throw new RuntimeException("no mappings to remap target "+name+" in namespace "+namespace);
				return;
			}

			int ns = tree.getNamespaceId(namespace);

			if (ns == MappingTreeView.NULL_NAMESPACE_ID) { // mappings missing src ns
				if (!optional) throw new RuntimeException("namespace "+namespace+" missing from mappings");
				return;
			}

			if (runtimeNamespaceId == MappingTreeView.NULL_NAMESPACE_ID) { // mappings missing dst ns
				if (!optional) throw new RuntimeException("runtime namespace "+runtimeNamespace+" missing from mappings");
				return;
			}

			this.runtimeClassName = tree.mapClassName(name, ns, runtimeNamespaceId);
		}
	}
}
