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

package net.fabricmc.loader.impl.discovery;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.ClassTweakerWriter;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.ManifestUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.TinyRemapperLoggerAdapter;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.OutputConsumerPath.ResourceRemapper;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public final class RuntimeModRemapper {
	private static final String REMAP_TYPE_MANIFEST_KEY = "Fabric-Loom-Mixin-Remap-Type";
	private static final String REMAP_TYPE_MIXIN = "mixin";
	private static final String REMAP_TYPE_STATIC = "static";

	public static void remap(Collection<ModCandidateImpl> modCandidates, Collection<ModCandidateImpl> cpMods, Path tmpDir, Path outputDir) {
		Set<ModCandidateImpl> modsToRemap = new HashSet<>();
		Set<InputTag> remapMixins = new HashSet<>();

		for (ModCandidateImpl mod : modCandidates) {
			if (mod.getRequiresRemap()) {
				modsToRemap.add(mod);
			}
		}

		if (modsToRemap.isEmpty()) return;

		MappingConfiguration config = FabricLauncherBase.getLauncher().getMappingConfiguration();
		String modNs = config.getDefaultModDistributionNamespace();
		String runtimeNs = config.getRuntimeNamespace();
		if (modNs.equals(runtimeNs) || !config.hasAnyMappings()) return;

		Map<ModCandidateImpl, RemapInfo> infoMap = new HashMap<>();

		TinyRemapper remapper = null;

		try {
			FabricLauncher launcher = FabricLauncherBase.getLauncher();

			ClassTweaker mergedClassTweaker = ClassTweaker.newInstance();
			mergedClassTweaker.visitHeader(modNs);

			for (ModCandidateImpl mod : cpMods) {
				RemapInfo info = new RemapInfo();
				infoMap.put(mod, info);

				if (mod.hasPath()) {
					info.inputPaths = mod.getPaths();
				} else {
					info.inputPaths = Collections.singletonList(mod.copyToDir(tmpDir, true));
					info.inputIsTemp = true;
				}

				String classTweaker = mod.getMetadata().getClassTweaker();

				if (classTweaker != null) {
					info.classTweakerPath = classTweaker;
					boolean found = false;

					for (Path inputPath : info.inputPaths) {
						try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(inputPath, false)) {
							Path ctPath = jarFs.get().getPath(classTweaker);

							if (Files.exists(ctPath)) {
								info.classTweaker = Files.readAllBytes(ctPath);
								found = true;
								break;
							}
						} catch (Throwable t) {
							throw new RuntimeException("Error reading class tweaker for mod '" +mod.getId()+ "'!", t);
						}
					}

					if (!found) {
						throw new RuntimeException("Missing class tweaker file "+classTweaker+" for mod " +mod.getId());
					}

					ClassTweakerReader.create(mergedClassTweaker).read(info.classTweaker, modNs);
				}
			}

			remapper = TinyRemapper.newRemapper(new TinyRemapperLoggerAdapter(LogCategory.MOD_REMAP))
					.withMappings(TinyUtils.createMappingProvider(launcher.getMappingConfiguration().getMappings(), modNs, runtimeNs))
					.renameInvalidLocals(false)
					.extension(new MixinExtension(remapMixins::contains))
					.extraAnalyzeVisitor((mrjVersion, className, next) ->
					mergedClassTweaker.createClassVisitor(FabricLoaderImpl.ASM_VERSION, next, null))
					.build();

			try {
				remapper.readClassPathAsync(getRemapClasspath().toArray(new Path[0]));
			} catch (IOException e) {
				throw new RuntimeException("Failed to populate remap classpath", e);
			}

			//gather inputs and class path

			String defaultMixinRemapType = System.getProperty(SystemProperties.DEFAULT_MIXIN_REMAP_TYPE, REMAP_TYPE_MIXIN);

			for (ModCandidateImpl mod : cpMods) {
				RemapInfo info = infoMap.get(mod);

				if (modsToRemap.contains(mod)) {
					InputTag tag = remapper.createInputTag();
					info.tag = tag;

					if (requiresMixinRemap(info.inputPaths, defaultMixinRemapType)) {
						remapMixins.add(tag);
					}

					info.outputPath = outputDir.resolve(mod.getDefaultFileName());
					Files.deleteIfExists(info.outputPath);

					remapper.readInputsAsync(tag, info.inputPaths.toArray(new Path[0]));
				} else {
					remapper.readClassPathAsync(info.inputPaths.toArray(new Path[0]));
				}
			}

			// copy non-classes, remap AWs, apply remapping

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				List<ResourceRemapper> resourceRemappers = NonClassCopyMode.FIX_META_INF.remappers;

				// aw remapping
				if (info.classTweaker != null) {
					ResourceRemapper ctRemapper = createClassTweakerRemapper(info, modNs, runtimeNs);

					if (ctRemapper != null) {
						resourceRemappers = new ArrayList<>(resourceRemappers);
						resourceRemappers.add(ctRemapper);
					}
				}

				try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(info.outputPath).build()) {
					for (Path path : info.inputPaths) {
						FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(path, false); // TODO: close properly

						if (delegate.get() == null) {
							throw new RuntimeException("Could not open JAR file " + path + " for NIO reading!");
						}

						Path inputJar = delegate.get().getRootDirectories().iterator().next();
						outputConsumer.addNonClassFiles(inputJar, remapper, resourceRemappers);
					}

					remapper.apply(outputConsumer, info.tag);
				}
			}

			remapper.finish();

			// update paths

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				mod.setPaths(Collections.singletonList(info.outputPath));
			}
		} catch (Throwable t) {
			if (remapper != null) {
				remapper.finish();
			}

			for (RemapInfo info : infoMap.values()) {
				if (info.outputPath == null) {
					continue;
				}

				try {
					Files.deleteIfExists(info.outputPath);
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting failed output jar %s", info.outputPath, e);
				}
			}

			throw new FormattedException("Failed to remap mods!", t);
		} finally {
			for (RemapInfo info : infoMap.values()) {
				try {
					if (info.inputIsTemp) {
						for (Path path : info.inputPaths) {
							Files.deleteIfExists(path);
						}
					}
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting temporary input jar %s", info.inputIsTemp, e);
				}
			}
		}
	}

	private static ResourceRemapper createClassTweakerRemapper(RemapInfo remapInfo, String modNs, String runtimeNs) {
		return new ResourceRemapper() {
			@Override
			public boolean canTransform(TinyRemapper remapper, Path relativePath) {
				return relativePath.toString().equals(remapInfo.classTweakerPath);
			}

			@Override
			public void transform(Path destinationDirectory, Path relativePath, InputStream input, TinyRemapper remapper) throws IOException {
				ClassTweakerWriter writer = ClassTweakerWriter.create(ClassTweaker.CT_LATEST);
				ClassTweakerRemapperVisitor remappingDecorator = new ClassTweakerRemapperVisitor(writer, remapper.getEnvironment().getRemapper(), modNs, runtimeNs);
				ClassTweakerReader reader = ClassTweakerReader.create(remappingDecorator);
				reader.read(input.readAllBytes(), modNs);

				Files.write(destinationDirectory.resolve(relativePath.toString()), writer.getOutput());
			}
		};
	}

	private static List<Path> getRemapClasspath() throws IOException {
		String remapClasspathFile = System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE);

		if (remapClasspathFile == null) {
			throw new RuntimeException("No remapClasspathFile provided");
		}

		String content = new String(Files.readAllBytes(Paths.get(remapClasspathFile)), StandardCharsets.UTF_8);

		return Arrays.stream(content.split(File.pathSeparator))
				.map(Paths::get)
				.collect(Collectors.toList());
	}

	/**
	 * Determine whether a jar requires Mixin remapping with tiny remapper.
	 *
	 * <p>This is typically the case when a mod was built without the Mixin annotation processor generating refmaps.
	 */
	private static boolean requiresMixinRemap(Collection<Path> inputPaths, String defaultMixinRemapType) throws IOException, URISyntaxException {
		for (Path inputPath : inputPaths) {
			Manifest manifest = ManifestUtil.readManifest(inputPath);
			if (manifest == null) continue;

			Attributes mainAttributes = manifest.getMainAttributes();

			String remapType = mainAttributes.getValue(REMAP_TYPE_MANIFEST_KEY);
			if (remapType == null) remapType = defaultMixinRemapType;

			if (REMAP_TYPE_STATIC.equalsIgnoreCase(remapType)) {
				return true;
			}
		}

		return false;
	}

	private static class RemapInfo {
		InputTag tag;
		List<Path> inputPaths;
		Path outputPath;
		boolean inputIsTemp;
		String classTweakerPath;
		byte[] classTweaker;
	}
}
