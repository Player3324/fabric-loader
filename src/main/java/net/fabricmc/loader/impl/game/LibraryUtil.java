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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.ManifestUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlConversionException;
import net.fabricmc.loader.impl.util.UrlUtil;

public final class LibraryUtil {
	public static LibraryLocation locate(String file) {
		Path path;
		List<Path> redundantPaths = Collections.emptyList();

		try {
			Enumeration<URL> urls = LibraryUtil.class.getClassLoader().getResources(file);

			if (!urls.hasMoreElements()) { // 0 paths
				path = null;
			} else { // 1+ paths
				path = UrlUtil.getCodeSource(urls.nextElement(), file);

				if (urls.hasMoreElements()) { // 2+ paths
					redundantPaths = new ArrayList<>();
					Version highestVersion = Version.parse(getVersion(path));

					do {
						Path curPath = UrlUtil.getCodeSource(urls.nextElement(), file);
						Version version = Version.parse(getVersion(curPath));

						if (version.compareTo(highestVersion) > 0) { // prioritize latest
							redundantPaths.add(path);
							path = curPath;
						} else {
							redundantPaths.add(curPath);
						}
					} while (urls.hasMoreElements());
				}
			}
		} catch (IOException | UrlConversionException | VersionParsingException e) {
			throw new RuntimeException(e);
		}

		return new LibraryLocation(path, redundantPaths);
	}

	public static final class LibraryLocation {
		public final Path path;
		public final List<Path> redundantPaths;

		LibraryLocation(Path path, List<Path> redundantPaths) {
			this.path = path;
			this.redundantPaths = redundantPaths;
		}
	}

	private static String getVersion(Path path) throws IOException {
		String ret = getVersionFromManifest(path);
		if (ret != null && !ret.isEmpty()) return ret;

		String name = path.getFileName().toString();
		Matcher matcher = fileNameVersionPattern.matcher(name);
		if (matcher.matches()) return matcher.group(1);

		return "1";
	}

	private static final Pattern fileNameVersionPattern = Pattern.compile(".+-(\\d.*)\\.[^\\.]+");

	private static String getVersionFromManifest(Path path) throws IOException {
		Manifest manifest = ManifestUtil.readManifest(path);
		if (manifest == null) return null;

		return ManifestUtil.getManifestValue(manifest, Name.IMPLEMENTATION_VERSION);
	}

	public static boolean hasUsableClassPath() {
		for (LoaderLibrary lib : LoaderLibrary.values()) {
			if (!lib.redundantPaths.isEmpty()) return false;
		}

		return true;
	}

	public static List<Path> getCleanClassPath(EnvType envType) {
		List<Path> ret = new ArrayList<>();

		// loader libs

		for (LoaderLibrary lib : LoaderLibrary.values()) {
			if (lib.path != null && lib.isApplicable(envType, false)) {
				ret.add(LoaderUtil.normalizeExistingPath(lib.path));
			}
		}

		// game provider

		try {
			String file = "META-INF/services/net.fabricmc.loader.impl.game.GameProvider";
			Enumeration<URL> urls = LibraryUtil.class.getClassLoader().getResources(file);

			while (urls.hasMoreElements()) {
				ret.add(LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(urls.nextElement(), file)));
			}
		} catch (IOException | UrlConversionException e) {
			throw new RuntimeException(e);
		}

		// system libraries property

		String systemLibProp = System.getProperty(SystemProperties.SYSTEM_LIBRARIES);

		if (systemLibProp != null) {
			for (String lib : systemLibProp.split(File.pathSeparator)) {
				Path path = Paths.get(lib);
				if (!Files.exists(path)) continue;

				path = LoaderUtil.normalizeExistingPath(path);

				if (!ret.contains(path)) {
					ret.add(path);
				}
			}
		}

		return ret;
	}
}
