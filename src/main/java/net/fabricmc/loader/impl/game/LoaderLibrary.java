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

package net.fabricmc.loader.impl.game;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.LibraryUtil.LibraryLocation;
import net.fabricmc.loader.impl.util.UrlUtil;

enum LoaderLibrary {
	FABRIC_LOADER(UrlUtil.LOADER_CODE_SOURCE),
	MAPPING_IO("net/fabricmc/mappingio/tree/MappingTree.class"),
	SPONGE_MIXIN("org/spongepowered/asm/launch/MixinBootstrap.class"),
	TINY_REMAPPER("net/fabricmc/tinyremapper/TinyRemapper.class"),
	ACCESS_WIDENER("net/fabricmc/accesswidener/AccessWidener.class"),
	ASM("org/objectweb/asm/ClassReader.class"),
	ASM_ANALYSIS("org/objectweb/asm/tree/analysis/Analyzer.class"),
	ASM_COMMONS("org/objectweb/asm/commons/Remapper.class"),
	ASM_TREE("org/objectweb/asm/tree/ClassNode.class"),
	ASM_UTIL("org/objectweb/asm/util/CheckClassAdapter.class"),
	SAT4J_CORE("org/sat4j/specs/ContradictionException.class"),
	SAT4J_PB("org/sat4j/pb/SolverFactory.class"),
	SERVER_LAUNCH("fabric-server-launch.properties", EnvType.SERVER), // installer generated jar to run setup loader's class path
	SERVER_LAUNCHER("net/fabricmc/installer/ServerLauncher.class", EnvType.SERVER), // installer based launch-through method
	JUNIT_API("org/junit/jupiter/api/Test.class"),
	JUNIT_PLATFORM_ENGINE("org/junit/platform/engine/TestEngine.class"),
	JUNIT_PLATFORM_LAUNCHER("org/junit/platform/launcher/core/LauncherFactory.class"),
	JUNIT_JUPITER("org/junit/jupiter/engine/JupiterTestEngine.class"),
	FABRIC_LOADER_JUNIT("net/fabricmc/loader/impl/junit/FabricLoaderLauncherSessionListener.class"),

	// Logging libraries are only loaded from the platform CL when running as a unit test.
	LOG4J_API("org/apache/logging/log4j/LogManager.class", true),
	LOG4J_CORE("META-INF/services/org.apache.logging.log4j.spi.Provider", true),
	LOG4J_CONFIG("log4j2.xml", true),
	LOG4J_PLUGIN_3("net/minecrell/terminalconsole/util/LoggerNamePatternSelector.class", true),
	SLF4J_API("org/slf4j/Logger.class", true);

	final Path path;
	final List<Path> redundantPaths;
	final EnvType env;
	final boolean junitRunOnly;

	LoaderLibrary(Path path) {
		if (path == null) throw new RuntimeException("missing loader library "+name());

		this.path = path;
		this.redundantPaths = Collections.emptyList();
		this.env = null;
		this.junitRunOnly = false;
	}

	LoaderLibrary(String file) {
		this(file, null, false);
	}

	LoaderLibrary(String file, EnvType env) {
		this(file, env, false);
	}

	LoaderLibrary(String path, boolean loggerLibrary) {
		this(path, null, loggerLibrary);
	}

	LoaderLibrary(String file, EnvType env, boolean junitRunOnly) {
		LibraryLocation loc = LibraryUtil.locate(file);

		this.path = loc.path;
		this.redundantPaths = loc.redundantPaths;
		this.env = env;
		this.junitRunOnly = junitRunOnly;
	}

	boolean isApplicable(EnvType env, boolean junitRun) {
		return (this.env == null || this.env == env)
				&& (!junitRunOnly || junitRun);
	}
}
