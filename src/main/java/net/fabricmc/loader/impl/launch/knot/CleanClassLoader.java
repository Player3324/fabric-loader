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

package net.fabricmc.loader.impl.launch.knot;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.LibraryUtil;
import net.fabricmc.loader.impl.util.UrlUtil;

final class CleanClassLoader extends URLClassLoader {
	static MethodHandle setup(EnvType envType) {
		if (LibraryUtil.hasUsableClassPath()) return null;

		List<Path> cp = LibraryUtil.getCleanClassPath(envType);
		URL[] urls = new URL[cp.size()];

		for (int i = 0; i < urls.length; i++) {
			try {
				urls[i] = UrlUtil.asUrl(cp.get(i));
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}

		@SuppressWarnings("resource")
		CleanClassLoader classLoader = new CleanClassLoader(urls);
		Thread.currentThread().setContextClassLoader(classLoader);

		MethodHandle invoker;

		try {
			Class<?> targetEnvTypeCls = classLoader.loadClass("net.fabricmc.api.EnvType");
			Object targetEnv = targetEnvTypeCls.getField(envType.name()).get(null);
			Class<?> targetCls = classLoader.loadClass("net.fabricmc.loader.impl.launch.knot.Knot");
			invoker = MethodHandles.publicLookup().findStatic(targetCls, "launch", MethodType.methodType(void.class, String[].class, targetEnvTypeCls));
			invoker = MethodHandles.insertArguments(invoker, 1, targetEnv);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}

		return invoker;
	}

	private CleanClassLoader(URL[] urls) {
		super(urls, getPlatformClassLoader());
	}

	private static ClassLoader getPlatformClassLoader() {
		try {
			return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null); // Java 9+ only
		} catch (NoSuchMethodException e) {
			return CleanClassLoader.class.getClassLoader().getParent(); // fall back to boot cl
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	static {
		registerAsParallelCapable();
	}
}
