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

package net.fabricmc.loader.impl.game.minecraft.patch;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.extension.transform.ClassTransformContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformPhases;
import net.fabricmc.loader.api.extension.transform.ClassTransformTargetAnalyzerContext;
import net.fabricmc.loader.api.extension.transform.ClassTransformer;
import net.fabricmc.loader.api.extension.transform.ClassTransformerBuilder;
import net.fabricmc.loader.api.extension.transform.TransformResult;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.minecraft.Hooks;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.transformer.ClassTransformHandler;
import net.fabricmc.loader.impl.transformer.TransformUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.version.VersionPredicateParser;

public class EntrypointPatch {
	private static final VersionPredicate VERSION_1_19_4 = createVersionPredicate(">=1.19.4-");
	private static final VersionPredicate VERSION_25w14craftmine = createVersionPredicate("1.21.6-alpha.25.14.craftmine");

	public static ClassTransformer<?> create(GameProvider gameProvider) {
		String entrypoint = gameProvider.getEntrypoint();

		if (!entrypoint.startsWith("net/minecraft/") && !entrypoint.startsWith("com/mojang/")) {
			return null;
		}

		PatchData data = new PatchData(FabricLauncherBase.getLauncher().getEnvironmentType(), entrypoint, getGameVersion(gameProvider), entrypoint.contains("Applet"));

		return ClassTransformerBuilder.create("entrypoint", ClassTransformHandler.FULL_CLASS_NODE_APPLICATOR)
				.setTargetAnalyzer(context -> findTarget(data, context))
				.setPhase(ClassTransformPhases.BEFORE_MIXIN)
				.setTransformer((classNode, context) -> transform(data, classNode, context))
				.build();
	}

	private static Collection<String> findTarget(PatchData data, ClassTransformTargetAnalyzerContext<ClassNode> context) {
		String gameEntrypoint = findGameEntrypoint(data, name -> context.getClass(name));

		if (!gameEntrypoint.equals(data.entrypoint) && !context.hasClass(gameEntrypoint)) {
			throw new RuntimeException("Could not load game class " + gameEntrypoint + "!");
		}

		Log.debug(LogCategory.GAME_PATCH, "Found game constructor: %s -> %s", data.entrypoint, gameEntrypoint);

		return Collections.singleton(gameEntrypoint);
	}

	private static String findGameEntrypoint(PatchData data, Function<String, ClassNode> classSource) {
		ClassNode mainClass = classSource.apply(data.entrypoint);

		if (mainClass == null) {
			throw new RuntimeException("Could not load main class " + data.entrypoint + "!");
		}

		// Main -> Game entrypoint search
		//
		// -- CLIENT --
		// pre-classic: find init() invocation before "Failed to start RubyDung" log message
		// pre-1.6 (seems to hold to 0.0.11!): find the only non-static non-java-packaged Object field
		// 1.6.1+: [client].start() [INVOKEVIRTUAL]
		// 19w04a: [client].<init> [INVOKESPECIAL] -> Thread.start()
		// -- SERVER --
		// (1.5-1.7?)-:     Just find it instantiating itself.
		// (1.6-1.8?)+:     an <init> starting with java.io.File can be assumed to be definite
		// (20w20b-20w21a): Now has its own main class, that constructs the server class. Find a specific regex string in the class.
		// (20w22a)+:       Datapacks are now reloaded in main. To ensure that mods load correctly, inject into Main after --safeMode check.

		if (data.envType == EnvType.CLIENT) {
			// pre-1.6 route
			List<FieldNode> newGameFields = TransformUtil.findFields(mainClass,
					(f) -> !TransformUtil.isStatic(f.access) && f.desc.startsWith("L") && !f.desc.startsWith("Ljava/")
					);

			if (newGameFields.size() == 1) {
				return Type.getType(newGameFields.get(0).desc).getInternalName();
			}
		}

		// main method searches
		MethodNode mainMethod = TransformUtil.findMethod(mainClass, (method) -> method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V") && TransformUtil.isPublicStatic(method.access));

		if (mainMethod == null) {
			throw new RuntimeException("Could not find main method in " + data.entrypoint + "!");
		}

		if (data.envType == EnvType.CLIENT && mainMethod.instructions.size() < 10) {
			// 22w24+ forwards to another method in the same class instead of processing in main() directly, use that other method instead if that's the case
			MethodInsnNode invocation = null;

			for (AbstractInsnNode insn : mainMethod.instructions) {
				MethodInsnNode methodInsn;

				if (invocation == null
						&& insn.getType() == AbstractInsnNode.METHOD_INSN
						&& (methodInsn = (MethodInsnNode) insn).owner.equals(mainClass.name)) {
					// capture first method insn to the same class
					invocation = methodInsn;
				} else if (insn.getOpcode() > Opcodes.ALOAD // ignore constant and variable loads as well as NOP, labels and line numbers
						&& insn.getOpcode() != Opcodes.RETURN) { // and RETURN
					// found unexpected insn for a simple forwarding method
					invocation = null;
					break;
				}
			}

			if (invocation != null) { // simple forwarder confirmed, use its target for further processing
				final MethodInsnNode reqMethod = invocation;
				mainMethod = TransformUtil.findMethod(mainClass, m -> m.name.equals(reqMethod.name) && m.desc.equals(reqMethod.desc));
			}
		} else if (data.envType == EnvType.SERVER) {
			// pre-1.6 method search route
			MethodInsnNode newGameInsn = (MethodInsnNode) TransformUtil.findInsn(mainMethod,
					(insn) -> insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && ((MethodInsnNode) insn).owner.equals(mainClass.name),
					false
					);

			if (newGameInsn != null) {
				data.serverHasFile = newGameInsn.desc.startsWith("(Ljava/io/File;");
				return newGameInsn.owner;
			}
		}

		// modern method search routes
		MethodInsnNode newGameInsn = (MethodInsnNode) TransformUtil.findInsn(mainMethod,
				data.envType == EnvType.CLIENT
				? (insn) -> (insn.getOpcode() == Opcodes.INVOKESPECIAL || insn.getOpcode() == Opcodes.INVOKEVIRTUAL) && !((MethodInsnNode) insn).owner.startsWith("java/")
						: (insn) -> insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && hasSuperClass(((MethodInsnNode) insn).owner, mainClass.name, classSource),
						true
				);

		// New 20w20b way of finding the server constructor
		if (newGameInsn == null && data.envType == EnvType.SERVER) {
			newGameInsn = (MethodInsnNode) TransformUtil.findInsn(mainMethod,
					insn -> (insn instanceof MethodInsnNode) && insn.getOpcode() == Opcodes.INVOKESPECIAL && hasStrInMethod(((MethodInsnNode) insn).owner, "<clinit>", "()V", "^[a-fA-F0-9]{40}$", classSource),
					false);
		}

		if (newGameInsn != null) {
			data.serverHasFile = newGameInsn.desc.startsWith("(Ljava/io/File;");
		}

		// Detect 20w22a by searching for a specific log message
		if (data.envType == EnvType.SERVER && hasStrInMethod(mainClass.name, mainMethod.name, mainMethod.desc, "Safe mode active, only vanilla datapack will be loaded", classSource)) {
			data.is20w22aServerOrHigher = true;
			return mainClass.name;
		}

		if (newGameInsn != null) {
			return newGameInsn.owner;
		}

		throw new RuntimeException("Could not find game constructor in " + data.entrypoint + "!");
	}

	private static TransformResult<ClassNode> transform(PatchData data, ClassNode gameClass, ClassTransformContext<ClassNode> context) {
		MethodNode gameMethod = null;
		MethodNode gameConstructor = null;
		AbstractInsnNode lwjglLogNode = null;
		AbstractInsnNode currentThreadNode = null;
		int gameMethodQuality = 0;

		if (!data.is20w22aServerOrHigher) {
			for (MethodNode gmCandidate : gameClass.methods) {
				if (gmCandidate.name.equals("<init>")) {
					gameConstructor = gmCandidate;

					if (gameMethodQuality < 1) {
						gameMethod = gmCandidate;
						gameMethodQuality = 1;
					}
				}

				if (data.envType == EnvType.CLIENT && !data.isApplet && gmCandidate.name.equals("run")) {
					// For pre-classic, try to find the "Failed to start RubyDung" log message
					// that is shown if the init() method throws an exception, then patch said
					// init() method.

					MethodInsnNode potentialInitInsn = null;
					boolean hasFailedToStartLog = false;

					for (AbstractInsnNode insn : gmCandidate.instructions) {
						if (insn instanceof MethodInsnNode && potentialInitInsn == null) {
							MethodInsnNode methodInsn = (MethodInsnNode) insn;

							if (methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL && methodInsn.owner.equals(gameClass.name)) {
								potentialInitInsn = methodInsn;
							} else {
								// first method insn is not init(), this is not pre-classic!
								break;
							}
						}

						if (insn instanceof LdcInsnNode && !hasFailedToStartLog) {
							if (potentialInitInsn == null) {
								// found LDC before init() invocation, this is not pre-classic!
								break;
							}

							Object cst = ((LdcInsnNode) insn).cst;

							if (cst instanceof String) {
								String s = (String) cst;

								if (s.equals("Failed to start RubyDung")) {
									hasFailedToStartLog = true;
								}
							}

							if (!hasFailedToStartLog) {
								// first LDC insn is not the expected log message, this is not pre-classic!
								break;
							}
						}

						if (potentialInitInsn != null && hasFailedToStartLog) {
							// found log message and init() invocation, now get the init() method node
							for (MethodNode gm : gameClass.methods) {
								if (gm.name.equals(potentialInitInsn.name) && gm.desc.equals(potentialInitInsn.desc)) {
									gameMethod = gm;
									gameMethodQuality = 2;

									break;
								}
							}
						}
					}
				}

				if (data.envType == EnvType.CLIENT && !data.isApplet && gameMethodQuality < 2) {
					// Try to find a method with an LDC string "LWJGL Version: ".
					// This is the "init()" method, or as of 19w38a is the constructor, or called somewhere in that vicinity,
					// and is by far superior in hooking into for a well-off mod start.
					// Also try and find a Thread.currentThread() call before the LWJGL version print.

					int qual = 2;
					boolean hasLwjglLog = false;

					for (AbstractInsnNode insn : gmCandidate.instructions) {
						if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode) {
							final MethodInsnNode methodInsn = (MethodInsnNode) insn;

							if ("currentThread".equals(methodInsn.name)
									&& "java/lang/Thread".equals(methodInsn.owner)
									&& "()Ljava/lang/Thread;".equals(methodInsn.desc)) {
								currentThreadNode = methodInsn;
							}
						} else if (insn instanceof LdcInsnNode) {
							Object cst = ((LdcInsnNode) insn).cst;

							if (cst instanceof String) {
								String s = (String) cst;

								//This log output was renamed to Backend library in 19w34a
								if (s.startsWith("LWJGL Version: ") || s.startsWith("Backend library: ")) {
									hasLwjglLog = true;

									if ("LWJGL Version: ".equals(s)
											|| "LWJGL Version: {}".equals(s)
											|| "Backend library: {}".equals(s)) {
										qual = 3;
										lwjglLogNode = insn;
									}

									break;
								}
							}
						}
					}

					if (hasLwjglLog) {
						gameMethod = gmCandidate;
						gameMethodQuality = qual;
					}
				}
			}
		} else {
			gameMethod = TransformUtil.findMethod(gameClass, (method) -> method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V") && TransformUtil.isPublicStatic(method.access));
		}

		if (gameMethod == null) {
			throw new RuntimeException("Could not find game constructor method in " + gameClass.name + "!");
		}

		boolean patched = false;
		Log.debug(LogCategory.GAME_PATCH, "Patching game constructor %s%s", gameMethod.name, gameMethod.desc);

		if (data.envType == EnvType.SERVER) {
			if (!data.is20w22aServerOrHigher) {
				patched = patchServerOld(data, gameClass, gameMethod);
			} else {
				patched = patchServer20w22aPlus(data, gameClass, gameMethod);
			}
		} else if (data.isApplet) { // applet client
			patched = patchClientApplet(data, gameClass, gameMethod, gameConstructor);
		} else { // non-applet client
			patched = patchClient(data, gameClass, gameMethod, gameConstructor,
					lwjglLogNode, currentThreadNode);
		}

		if (!patched) {
			throw new RuntimeException("Game constructor patch not applied!");
		}

		if (data.isApplet) {
			Hooks.appletMainClass = data.entrypoint;
		}

		return TransformResult.changed(gameClass);
	}

	/**
	 * Patch server class from before 20w22a.
	 */
	private static boolean patchServerOld(PatchData data, ClassNode gameClass, MethodNode gameMethod) {
		ListIterator<AbstractInsnNode> it = gameMethod.instructions.iterator();

		// Server-side: first argument (or null!) is runDirectory, run at end of init
		TransformUtil.moveBefore(it, Opcodes.RETURN);

		// runDirectory
		if (data.serverHasFile) {
			it.add(new VarInsnNode(Opcodes.ALOAD, 1));
		} else {
			it.add(new InsnNode(Opcodes.ACONST_NULL));
		}

		it.add(new VarInsnNode(Opcodes.ALOAD, 0));

		finishEntrypoint(data.envType, it);

		return true;
	}

	/**
	 * Patch server class for 20w22a or newer.
	 */
	private static boolean patchServer20w22aPlus(PatchData data, ClassNode gameClass, MethodNode gameMethod) {
		ListIterator<AbstractInsnNode> it = gameMethod.instructions.iterator();

		// Server-side: Run before `server.properties` is loaded so early logic like world generation is not broken due to being loaded by server properties before mods are initialized.
		// ----------------
		// ldc "server.properties"
		// iconst_0
		// anewarray java/lang/String
		// invokestatic java/nio/file/Paths.get (Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;
		// ----------------
		Log.debug(LogCategory.GAME_PATCH, "20w22a+ detected, patching main method...");

		// Find the "server.properties".
		LdcInsnNode serverPropertiesLdc = (LdcInsnNode) TransformUtil.findInsn(gameMethod, insn -> insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst.equals("server.properties"), false);

		// Move before the `server.properties` ldc is pushed onto stack
		TransformUtil.moveBefore(it, serverPropertiesLdc);

		// Detect if we are running exactly 20w22a.
		// Find the synthetic method where dedicated server instance is created so we can set the game instance.
		// This cannot be the main method, must be static (all methods are static, so useless to check)
		// Cannot return a void or boolean
		// Is only method that returns a class instance
		// If we do not find this, then we are certain this is 20w22a.
		MethodNode serverStartMethod = TransformUtil.findMethod(gameClass, method -> {
			if ((method.access & Opcodes.ACC_SYNTHETIC) == 0 // reject non-synthetic
					|| method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V") // reject main method (theoretically superfluous now)
					|| VERSION_25w14craftmine.test(data.gameVersion) && method.parameters.size() < 10) { // reject problematic extra methods
				return false;
			}

			final Type methodReturnType = Type.getReturnType(method.desc);

			return methodReturnType.getSort() != Type.BOOLEAN && methodReturnType.getSort() != Type.VOID && methodReturnType.getSort() == Type.OBJECT;
		});

		if (serverStartMethod == null) {
			// We are running 20w22a, this requires a separate process for capturing game instance
			Log.debug(LogCategory.GAME_PATCH, "Detected 20w22a");
		} else {
			Log.debug(LogCategory.GAME_PATCH, "Detected version above 20w22a");
			// We are not running 20w22a.
			// This means we need to position ourselves before any dynamic registries are initialized.
			// Since it is a bit hard to figure out if we are on most 1.16-pre1+ versions.
			// So if the version is below 1.16.2-pre2, this injection will be before the timer thread hack. This should have no adverse effects.

			// This diagram shows the intended result for 1.16.2-pre2
			// ----------------
			// invokestatic ... Bootstrap log missing
			// <---- target here (1.16-pre1 to 1.16.2-pre1)
			// ...misc
			// invokestatic ... (Timer Thread Hack)
			// <---- target here (1.16.2-pre2+)
			// ... misc
			// invokestatic ... (Registry Manager) [Only present in 1.16.2-pre2+]
			// ldc "server.properties"
			// ----------------

			// The invokestatic insn we want is just before the ldc
			AbstractInsnNode previous = serverPropertiesLdc.getPrevious();

			while (true) {
				if (previous == null) {
					throw new RuntimeException("Failed to find static method before loading server properties");
				}

				if (previous.getOpcode() == Opcodes.INVOKESTATIC) {
					break;
				}

				previous = previous.getPrevious();
			}

			boolean foundNode = false;

			// Move the iterator back till we are just before the insn node we wanted
			while (it.hasPrevious()) {
				if (it.previous() == previous) {
					if (it.hasPrevious()) {
						foundNode = true;
						// Move just before the method insn node
						it.previous();
					}

					break;
				}
			}

			if (!foundNode) {
				throw new RuntimeException("Failed to find static method before loading server properties");
			}
		}

		it.add(new InsnNode(Opcodes.ACONST_NULL));

		// Pass null for now, we will set the game instance when the dedicated server is created.
		it.add(new InsnNode(Opcodes.ACONST_NULL));

		finishEntrypoint(data.envType, it); // Inject the hook entrypoint.

		// Time to find the dedicated server ctor to capture game instance
		if (serverStartMethod == null) {
			// FIXME: For 20w22a, find the only constructor in the game method that takes a DataFixer.
			// That is the guaranteed to be dedicated server constructor
			Log.debug(LogCategory.GAME_PATCH, "Server game instance has not be implemented yet for 20w22a");
		} else {
			final ListIterator<AbstractInsnNode> serverStartIt = serverStartMethod.instructions.iterator();

			// 1.16-pre1+ Find the only constructor which takes a Thread as it's first parameter
			MethodInsnNode dedicatedServerConstructor = (MethodInsnNode) TransformUtil.findInsn(serverStartMethod, insn -> {
				if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("<init>")) {
					Type constructorType = Type.getMethodType(((MethodInsnNode) insn).desc);

					if (constructorType.getArgumentTypes().length <= 0) {
						return false;
					}

					return constructorType.getArgumentTypes()[0].getDescriptor().equals("Ljava/lang/Thread;");
				}

				return false;
			}, false);

			if (dedicatedServerConstructor == null) {
				throw new RuntimeException("Could not find dedicated server constructor");
			}

			// Jump after the <init> call
			TransformUtil.moveAfter(serverStartIt, dedicatedServerConstructor);

			// Duplicate dedicated server instance for loader
			serverStartIt.add(new InsnNode(Opcodes.DUP));
			serverStartIt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Hooks.INTERNAL_NAME, "setGameInstance", "(Ljava/lang/Object;)V", false));
		}

		return true;
	}

	private static boolean patchClientApplet(PatchData data, ClassNode gameClass, MethodNode gameMethod, MethodNode gameConstructor) {
		// Applet-side: field is private static File, run at end
		// At the beginning, set file field (hook)
		FieldNode runDirectory = TransformUtil.findField(gameClass, (f) -> TransformUtil.isStatic(f.access) && f.desc.equals("Ljava/io/File;"));

		if (runDirectory == null) {
			// TODO: Handle pre-indev versions.
			//
			// Classic has no agreed-upon run directory.
			// - level.dat is always stored in CWD. We can assume CWD is set, launchers generally adhere to that.
			// - options.txt in newer Classic versions is stored in user.home/.minecraft/. This is not currently handled,
			// but as these versions are relatively low on options this is not a huge concern.
			Log.warn(LogCategory.GAME_PATCH, "Could not find applet run directory! (If you're running pre-late-indev versions, this is fine.)");

			ListIterator<AbstractInsnNode> it = gameMethod.instructions.iterator();

			if (gameConstructor == gameMethod) {
				TransformUtil.moveBefore(it, Opcodes.RETURN);
			}

			/*it.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
							it.add(new InsnNode(Opcodes.DUP));
							it.add(new LdcInsnNode("."));
							it.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)); */
			it.add(new InsnNode(Opcodes.ACONST_NULL));
			it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/impl/game/minecraft/applet/AppletMain", "hookGameDir", "(Ljava/io/File;)Ljava/io/File;", false));
			it.add(new VarInsnNode(Opcodes.ALOAD, 0));
			finishEntrypoint(data.envType, it);
		} else {
			// Indev and above.
			ListIterator<AbstractInsnNode> it = gameConstructor.instructions.iterator();
			TransformUtil.moveAfter(it, Opcodes.INVOKESPECIAL); /* Object.init */
			it.add(new FieldInsnNode(Opcodes.GETSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));
			it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/impl/game/minecraft/applet/AppletMain", "hookGameDir", "(Ljava/io/File;)Ljava/io/File;", false));
			it.add(new FieldInsnNode(Opcodes.PUTSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));

			it = gameMethod.instructions.iterator();

			if (gameConstructor == gameMethod) {
				TransformUtil.moveBefore(it, Opcodes.RETURN);
			}

			it.add(new FieldInsnNode(Opcodes.GETSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));
			it.add(new VarInsnNode(Opcodes.ALOAD, 0));
			finishEntrypoint(data.envType, it);
		}

		return true;
	}

	private static boolean patchClient(PatchData data, ClassNode gameClass, MethodNode gameMethod, MethodNode gameConstructor,
			AbstractInsnNode lwjglLogNode, AbstractInsnNode currentThreadNode) {
		// Client-side:
		// - if constructor, identify runDirectory field + location, run immediately after
		// - if non-constructor (init method), head

		if (gameConstructor == null) {
			throw new RuntimeException("Non-applet client-side, but could not find constructor?");
		}

		ListIterator<AbstractInsnNode> consIt = gameConstructor.instructions.iterator();

		while (consIt.hasNext()) {
			AbstractInsnNode insn = consIt.next();
			if (insn.getOpcode() == Opcodes.PUTFIELD
					&& ((FieldInsnNode) insn).desc.equals("Ljava/io/File;")) {
				Log.debug(LogCategory.GAME_PATCH, "Run directory field is thought to be %s/%s", ((FieldInsnNode) insn).owner, ((FieldInsnNode) insn).name);

				ListIterator<AbstractInsnNode> it;

				if (gameMethod == gameConstructor) {
					it = consIt;
				} else {
					it = gameMethod.instructions.iterator();
				}

				// Add the hook just before the Thread.currentThread() call for 1.19.4 or later
				// If older 4 method insn's before the lwjgl log
				if (currentThreadNode != null && VERSION_1_19_4.test(data.gameVersion)) {
					TransformUtil.moveBefore(it, currentThreadNode);
				} else if (lwjglLogNode != null) {
					TransformUtil.moveBefore(it, lwjglLogNode);

					for (int i = 0; i < 4; i++) {
						TransformUtil.moveBeforeType(it, AbstractInsnNode.METHOD_INSN);
					}
				}

				it.add(new VarInsnNode(Opcodes.ALOAD, 0));
				it.add(new FieldInsnNode(Opcodes.GETFIELD, ((FieldInsnNode) insn).owner, ((FieldInsnNode) insn).name, ((FieldInsnNode) insn).desc));
				it.add(new VarInsnNode(Opcodes.ALOAD, 0));
				finishEntrypoint(data.envType, it);

				return true;
			}
		}

		// TODO: better handling of run directory for pre-classic
		if (gameMethod != gameConstructor) {
			ListIterator<AbstractInsnNode> it = gameMethod.instructions.iterator();

			it.add(new InsnNode(Opcodes.ACONST_NULL));
			it.add(new VarInsnNode(Opcodes.ALOAD, 0));
			finishEntrypoint(data.envType, it);

			return true;
		}

		return false;
	}

	private static final class PatchData {
		final EnvType envType;
		final String entrypoint;
		final Version gameVersion;
		final boolean isApplet;
		boolean serverHasFile = true;
		boolean is20w22aServerOrHigher;

		PatchData(EnvType envType, String entrypoint, Version gameVersion, boolean isApplet) {
			this.envType = envType;
			this.entrypoint = entrypoint;
			this.gameVersion = gameVersion;
			this.isApplet = isApplet;
		}
	}

	private static boolean hasSuperClass(String cls, String superCls, Function<String, ClassNode> classSource) {
		if (cls.contains("$") || (!cls.startsWith("net/minecraft") && cls.contains("/"))) {
			return false;
		}

		ClassNode classNode = classSource.apply(cls);

		return classNode != null && classNode.superName.equals(superCls);
	}

	private static boolean hasStrInMethod(String cls, String methodName, String methodDesc, String str, Function<String, ClassNode> classSource) {
		if (cls.contains("$") || (!cls.startsWith("net/minecraft") && cls.contains("/"))) {
			return false;
		}

		ClassNode node = classSource.apply(cls);
		if (node == null) return false;

		for (MethodNode method : node.methods) {
			if (method.name.equals(methodName) && method.desc.equals(methodDesc)) {
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof LdcInsnNode) {
						Object cst = ((LdcInsnNode) insn).cst;

						if (cst instanceof String) {
							if (cst.equals(str)) {
								return true;
							}
						}
					}
				}

				break;
			}
		}

		return false;
	}

	private static void finishEntrypoint(EnvType type, ListIterator<AbstractInsnNode> it) {
		String methodName = String.format("start%s", type == EnvType.CLIENT ? "Client" : "Server");
		it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Hooks.INTERNAL_NAME, methodName, "(Ljava/io/File;Ljava/lang/Object;)V", false));
	}

	private static Version getGameVersion(GameProvider gameProvider) {
		try {
			return Version.parse(gameProvider.getNormalizedGameVersion());
		} catch (VersionParsingException e) {
			throw new RuntimeException(e);
		}
	}

	private static VersionPredicate createVersionPredicate(String predicate) {
		try {
			return VersionPredicateParser.parse(predicate);
		} catch (VersionParsingException e) {
			throw new RuntimeException(e);
		}
	}
}
