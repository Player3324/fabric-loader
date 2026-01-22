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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loader.api.extension.transform.ClassTransformApplicator;
import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformPhases;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.PhaseSorting;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.mappingio.tree.MappingTreeView;

public final class ClassTransformHandler {
	private static final String[] DEFAULT_PHASE_ORDER = {
			ClassTransformPhases.PATCH,
			ClassTransformPhases.BEFORE_MIXIN,
			ClassTransformPhases.MIXIN,
			ClassTransformPhases.AFTER_MIXIN,
			ClassTransformPhases.DEFAULT,
			ClassTransformPhases.LATE
	};

	private static final boolean LOG_TRANSFORM_ERRORS = SystemProperties.isSet(SystemProperties.DEBUG_LOG_TRANSFORM_ERRORS);

	public static final ClassTransformApplicator<ByteBuffer, ?> BYTE_BUFFER_APPLICATOR = new ByteBufferApplicator();
	public static final ClassTransformApplicator<ClassNode, ?> FULL_CLASS_NODE_APPLICATOR = new ClassNodeApplicator(ClassReader.EXPAND_FRAMES, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

	private static final Map<Class<?>, Map<String, ClassTransformApplicator<?, ?>>> applicators = new IdentityHashMap<>();
	private static final List<ClassTransformerImpl<?>> transformers = new ArrayList<>();
	private static List<ClassTransformerImpl<?>> sortedTransformers;
	private static volatile boolean active;

	static {
		addApplicator(ByteBuffer.class, "plain", BYTE_BUFFER_APPLICATOR);
		addApplicator(ClassNode.class, "full", FULL_CLASS_NODE_APPLICATOR);
		addInternalTransformer(MixinTransformer.create());
	}

	public static <T> boolean addApplicator(Class<T> type, String subType, ClassTransformApplicator<T, ?> applicator) {
		Map<String, ClassTransformApplicator<?, ?>> typeApplicators = applicators.get(type);

		if (typeApplicators == null) {
			typeApplicators = new HashMap<>(1);
			applicators.put(type, typeApplicators);
		}

		return typeApplicators.putIfAbsent(subType, applicator) == null;
	}

	@SuppressWarnings("unchecked")
	public static <T> ClassTransformApplicator<T, ?> getApplicator(Class<T> type, String subType) {
		Map<String, ClassTransformApplicator<?, ?>> typeApplicators = applicators.get(type);
		if (typeApplicators == null) return null;

		return (ClassTransformApplicator<T, ?>) typeApplicators.get(subType);
	}

	public static <T> void addInternalTransformer(ClassTransformer<T> transformer) {
		addTransformer((ClassTransformerImpl<T>) transformer, FabricLoaderImpl.MOD_ID);
	}

	public static <T> void addTransformer(ClassTransformerImpl<T> transformer, String extensionModId) {
		if (active) throw new IllegalStateException("adding transformer too late");

		for (ClassTransformerImpl<?> entry : transformers) {
			if (entry.extensionModId.equals(extensionModId) && entry.name.equals(transformer.name)) {
				throw new RuntimeException(String.format("transformer %s:%s was already added before", extensionModId, transformer.name));
			}
		}

		transformer.extensionModId = extensionModId;

		transformers.add(transformer);
	}

	public static void activate() {
		assert !active;

		initNames();
		runTargetAnalyzers();
		sortPhases();

		active = true;
	}

	private static void initNames() {
		// this impl tries to only load mappings if necessary - when transformers have targets in a namespace other than the runtime ns
		MappingConfiguration config = FabricLauncherBase.getLauncher().getMappingConfiguration();

		String runtimeNamespace = config.getRuntimeNamespace();
		MappingTreeView tree = null;
		int runtimeNamespaceId = MappingTreeView.NULL_NAMESPACE_ID;

		boolean mappingsInitialized = false;

		for (ClassTransformerImpl<?> transformer : transformers) {
			if (transformer.targets.isEmpty()) continue;

			try {
				for (ClassTransformerImpl.Target target : transformer.targets) {
					if (!mappingsInitialized) {
						if (target.initRuntimeNameNoop(runtimeNamespace)) continue;

						tree = config.getMappings();
						if (tree != null) runtimeNamespaceId = tree.getNamespaceId(runtimeNamespace);
						mappingsInitialized = true;
					}

					target.initRuntimeName(runtimeNamespace, tree, runtimeNamespaceId);
				}
			} catch (Throwable t) {
				throw new RuntimeException("Error initializing target names for transformer "+transformer, t);
			}
		}
	}

	private static void runTargetAnalyzers() {
		List<ClassTransformerImpl<?>> transformersWithAnalyzers = new ArrayList<>();

		for (ClassTransformerImpl<?> transformer : transformers) {
			if (transformer.targetAnalyzer != null) {
				transformersWithAnalyzers.add(transformer);
			}
		}

		if (transformersWithAnalyzers.isEmpty()) return;

		if (transformersWithAnalyzers.size() == 1) {
			invokeTargetAnalyzer(transformersWithAnalyzers.get(0));
		} else {
			transformersWithAnalyzers.parallelStream().forEach(ClassTransformHandler::invokeTargetAnalyzer);
		}
	}

	private static <T> void invokeTargetAnalyzer(ClassTransformerImpl<T> transformer) {
		ClassTransformTargetAnalyzerContextImpl<T> context = new ClassTransformTargetAnalyzerContextImpl<>(transformer.applicator);

		Collection<String> targets;

		try {
			targets = transformer.targetAnalyzer.apply(context);
		} catch (Throwable t) {
			throw new RuntimeException("Error invoking target analyzer for transformer "+transformer, t);
		}

		if (targets.isEmpty()) return;

		for (String target : targets) {
			if (target.indexOf('.') >= 0) {
				throw new RuntimeException("name obtained from the target analyzer for transformer "+transformer+" needs to be in slash form (some/pkg/cls): "+target);
			}

			transformer.targets.add(new ClassTransformerImpl.Target(target, null, false));
		}
	}

	private static void sortPhases() {
		PhaseSorting<String, ClassTransformerImpl<?>> sorting = new PhaseSorting<>();

		sorting.addPhaseOrdering(DEFAULT_PHASE_ORDER);

		for (ClassTransformerImpl<?> transformer : transformers) {
			sorting.add(transformer.phase, transformer);
		}

		sortedTransformers = sorting.getAll();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static ByteBuffer transform(ByteBuffer input, String runtimeClassName) {
		if (!active) return input;

		List<ClassTransformerImpl<?>> currentTransformers = new ArrayList<>();
		boolean checkGenerate = input == null;

		for (ClassTransformerImpl<?> transformer : sortedTransformers) {
			if (checkGenerate && !transformer.canGenerate()
					|| !transformer.appliesTo(runtimeClassName)) {
				continue;
			}

			checkGenerate = false; // transformer may create the class, assume it exists while checking later transformers for the same applicator

			if (!currentTransformers.isEmpty() && transformer.applicator != currentTransformers.get(0).applicator) {
				input = invokeApplicator(input, runtimeClassName, (List) currentTransformers);
				checkGenerate = input == null;
				currentTransformers.clear();
			}

			currentTransformers.add(transformer);
		}

		if (!currentTransformers.isEmpty()) {
			input = invokeApplicator(input, runtimeClassName, (List) currentTransformers);
		}

		return input;
	}

	private static <T, C> ByteBuffer invokeApplicator(ByteBuffer input, String runtimeClassName, List<ClassTransformerImpl<T>> transformers) {
		@SuppressWarnings("unchecked")
		ClassTransformApplicator<T, C> applicator = (ClassTransformApplicator<T, C>) transformers.get(0).applicator;
		ClassTransformContext<T> context = new ClassTransformContextImpl<>(runtimeClassName);

		C current = applicator.setup(input);

		boolean hasData = input != null;
		assert hasData == applicator.hasData(current);

		for (ClassTransformerImpl<T> transformer : transformers) {
			try {
				if (!hasData) {
					if (!transformer.canGenerate()) continue;

					current = applicator.updateGenerate(current, transformer, context);
					hasData = applicator.hasData(current);
				} else {
					if (!transformer.canTransform()) continue;

					current = applicator.updateTransform(current, transformer, context);
					assert applicator.hasData(current);
				}
			} catch (Throwable t) {
				String msg = String.format("error transforming %s with %s",
						runtimeClassName,
						transformer);

				if (LOG_TRANSFORM_ERRORS) Log.warn(LogCategory.KNOT, msg, t);

				throw new RuntimeException(msg, t);
			}
		}

		return hasData ? applicator.finish(current, context) : null;
	}

	static <T, S> T getData(ByteBuffer input, ClassTransformApplicator<T, S> applicator) {
		return applicator.getData(applicator.setup(input));
	}
}
