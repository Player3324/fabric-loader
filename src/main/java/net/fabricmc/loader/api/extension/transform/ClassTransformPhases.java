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

package net.fabricmc.loader.api.extension.transform;

public final class ClassTransformPhases {
	public static final String PATCH = "patch";
	public static final String BEFORE_MIXIN = "beforeMixin";
	public static final String MIXIN = "mixin";
	public static final String AFTER_MIXIN = "afterMixin";
	public static final String DEFAULT = "default";
	public static final String LATE = "late";
}
