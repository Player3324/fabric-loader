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

import net.fabricmc.api.EnvType;

public class KnotServer {
	public static void main(String[] args) {
		MethodHandle mh = CleanClassLoader.setup(EnvType.SERVER);

		if (mh != null) {
			try {
				mh.invokeExact(args);
			} catch (RuntimeException e) {
				throw e;
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		} else {
			Knot.launch(args, EnvType.SERVER);
		}
	}
}
