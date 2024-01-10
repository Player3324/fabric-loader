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

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import net.fabricmc.loader.api.extension.transform.ClassTransformContext;

final class ClassTransformContextImpl<T> implements ClassTransformContext<T> {
	private static final String objectClass = "java/lang/Object";

	private final String runtimeClassName;

	ClassTransformContextImpl(String runtimeClassName) {
		this.runtimeClassName = runtimeClassName;
	}

	@Override
	public String getRuntimeClassName() {
		return runtimeClassName;
	}

	@Override
	public @Nullable ByteBuffer getClassBytes(String runtimeClassName) {
		if (runtimeClassName.indexOf('.') >= 0) throw new IllegalArgumentException("name needs to be in slash form (some/pkg/cls): "+runtimeClassName);

		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getCommonSuperClass(String clsA, String clsB) {
		assert clsA.indexOf('.') < 0 && clsB.indexOf('.') < 0;

		if (clsA.equals(clsB)) return clsA;
		if (clsA.equals(objectClass) || clsB.equals(objectClass)) return objectClass;

		/*ByteBuffer clsBytesA = getClassBytes(clsA);
		if (clsBytesA == null) throw new TypeNotPresentException(clsA, null);
		ByteBuffer clsBytesB = getClassBytes(clsB);
		if (clsBytesB == null) throw new TypeNotPresentException(clsB, null);

		ClassReader clsReaderA = LoaderUtil.deserializeClass(clsBytesA);
		ClassReader clsReaderB = LoaderUtil.deserializeClass(clsBytesB);

		if ((clsReaderA.getAccess() & Opcodes.ACC_INTERFACE) != 0) { // a is interface
			if ((clsReaderB.getAccess() & Opcodes.ACC_INTERFACE) != 0) { // b is interface
			}
		}*/

		return ClassInfo.getCommonSuperClass(clsA, clsB).getName();
	}
}
