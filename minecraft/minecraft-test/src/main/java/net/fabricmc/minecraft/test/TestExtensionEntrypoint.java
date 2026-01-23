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

package net.fabricmc.minecraft.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.extension.LoaderExtensionApi;
import net.fabricmc.loader.api.extension.LoaderExtensionEntrypoint;
import net.fabricmc.loader.api.extension.ModCandidate;
import net.fabricmc.loader.api.extension.ModMetadataBuilder;
import net.fabricmc.loader.api.metadata.ModDependency;

public class TestExtensionEntrypoint implements LoaderExtensionEntrypoint {
	@Override
	public void initExtension(LoaderExtensionApi api) {
		try {
			// Test addModSource - Creates a mod "extension_test_mod" that is only loaded if another mod depends on it.
			Path modDir = Files.createTempDirectory("extension_test_mod");
			ModCandidate testMod = api.createMod(
					List.of(modDir),
					ModMetadataBuilder.create()
							.setId("extension_test_mod")
							.setVersion("1.0.0")
							.addEntrypoint("main", "net.fabricmc.minecraft.test.extension.TestExtensionModEntrypoint")
							.build(),
					Collections.emptyList()
			);
			api.addModSource((dep) -> {
				if (dep.getModId().equals(testMod.getId())) {
					return testMod;
				}

				return null;
			});

			// Test addToClassPath and addMixinConfig - Creates a config with mixins that don't already exist in one.
			Path mixinDir = Files.createTempDirectory("extension_test_mixins");
			Path mixinConfig = Files.createTempFile(mixinDir, "mixins", ".json");
			Files.writeString(mixinConfig, "{"
					+ "\"required\": true, "
					+ "\"package\": \"net.fabricmc.minecraft.test.extension.mixin\", "
					+ "\"compatibilityLevel\": \"JAVA_8\", "
					+ "\"mixins\": [\"MixinFlintAndSteelItem\", \"MixinSoulFireBlock\"],"
					+ "\"injectors\": {\"defaultRequire\": 1},"
					// Note: Depending on your use case you may need to BYO refmap.
					// We happen to be able to use the one that's compiled with the build of the test mod.
					+ "\"refmap\": \"minecraft-test-refmap.json\""
					+ "}"
			);
			api.addToClassPath(mixinDir);
			Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("minecraft-test");
			api.addMixinConfig(container.orElseThrow(), mixinConfig.getFileName().toString());

			// Test addMod & addModSource - Creates another mod "extension_test_mod_two" that depends on extension_test_mod.
			// This dependency should cause TestExtensionModEntrypoint to run.
			Path modDir2 = Files.createTempDirectory("extension-test-mod-two");
			ModCandidate testMod2 = api.createMod(
					List.of(modDir2),
					ModMetadataBuilder.create()
							.setId("extension_test_mod_two")
							.setVersion("1.0.0")
							.addDependency(
									ModMetadataBuilder.ModDependencyBuilder
											.create(ModDependency.Kind.DEPENDS, "extension_test_mod")
											.build()
							)
							.build(),
					Collections.emptyList()
			);
			api.addMod(testMod2);
		} catch (VersionParsingException | IOException e) {
			throw new RuntimeException(e);
		}
	}
}
