package net.fabricmc.loader.impl.discovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.Expression;
import net.fabricmc.loader.impl.util.ExpressionFunctions;

public final class ResolutionContext {
	final Collection<ModCandidateImpl> initialMods;
	public final EnvType envType;
	public final Map<String, Expression.DynamicFunction> expressionFunctions;
	final Map<String, Set<ModCandidateImpl>> envDisabledMods;
	final ModResolver.PhaseSelectHandler phaseSelectHandler;

	final List<ModCandidateImpl> allModsSorted;
	final Map<String, List<ModCandidateImpl>> modsById = new LinkedHashMap<>(); // linked to ensure consistent execution
	final Map<String, ModCandidateImpl> selectedMods;
	final List<ModCandidateImpl> uniqueSelectedMods;

	final List<ModCandidateImpl> addedMods = new ArrayList<>();
	final List<ModCandidateImpl> currentSelectedMods = new ArrayList<>();

	public ResolutionContext(Collection<ModCandidateImpl> candidates,
							EnvType envType, Map<String, Expression.DynamicFunction> expressionFunctions,
							Map<String, Set<ModCandidateImpl>> envDisabledMods,
							ModResolver.PhaseSelectHandler phaseSelectHandler) {
		this.initialMods = candidates;
		this.envType = envType;
		this.expressionFunctions = expressionFunctions;
		this.envDisabledMods = envDisabledMods;
		this.phaseSelectHandler = phaseSelectHandler;

		this.allModsSorted = new ArrayList<>(candidates.size());
		this.selectedMods = new HashMap<>(candidates.size());
		this.uniqueSelectedMods = new ArrayList<>(candidates.size());

		expressionFunctions.put("mod", args -> {
			ExpressionFunctions.checkString1(args);

			return selectedMods.containsKey(args[0]) ? true : null;
		});
	}

	public Collection<ModCandidateImpl> getMods(String id) {
		List<ModCandidateImpl> ret = new ArrayList<>(modsById.getOrDefault(id, Collections.emptyList()));
		ModCandidateImpl mod = selectedMods.get(id);
		if (mod != null) ret.add(mod);

		for (ModCandidateImpl m : addedMods) {
			if (m.getId().equals(id)) ret.add(m);
		}

		return ret;
	}

	public Collection<ModCandidateImpl> getMods() {
		List<ModCandidateImpl> ret = new ArrayList<>(allModsSorted.size() + uniqueSelectedMods.size() + addedMods.size());
		ret.addAll(allModsSorted);
		ret.addAll(uniqueSelectedMods);
		ret.addAll(addedMods);

		return ret;
	}

	public boolean addMod(ModCandidateImpl mod) {
		for (ModCandidateImpl m : modsById.getOrDefault(mod.getId(), Collections.emptyList())) {
			if (m == mod || m.getVersion().equals(mod.getVersion())) {
				return false;
			}
		}

		if (selectedMods.containsKey(mod.getId())) return false;

		for (ModCandidateImpl m : addedMods) {
			if (m == mod || m.getId().equals(mod.getId()) && m.getVersion().equals(mod.getVersion())) {
				return false;
			}
		}

		addedMods.add(mod);

		for (ModCandidateImpl m : mod.getContainedMods()) {
			addMod(m);
		}

		return true;
	}

	public boolean removeMod(ModCandidateImpl mod) {
		if (selectedMods.get(mod.getId()) == mod) return false; // already loaded

		if (!mod.getContainedMods().isEmpty()) { // also remove all mods that'd become orphaned (check if possible first, then apply)
			Set<ModCandidateImpl> modsToRemove = Collections.newSetFromMap(new IdentityHashMap<>());
			modsToRemove.add(mod);
			Queue<ModCandidateImpl> queue = new ArrayDeque<>();
			ModCandidateImpl parent = mod;

			do {
				for (ModCandidateImpl m : parent.getContainedMods()) {
					if ((m.getContainingMods().size() == 1 || modsToRemove.containsAll(m.getContainingMods())) // orphaned
							&& modsToRemove.add(m)) {
						if (selectedMods.get(m.getId()) == m) return false;
						queue.add(m);
					}
				}
			} while ((parent = queue.poll()) != null);

			for (ModCandidateImpl m : modsToRemove) {
				if (m != mod) removeMod0(m);
			}
		}

		return removeMod0(mod);
	}

	private boolean removeMod0(ModCandidateImpl mod) {
		String id = mod.getId();
		List<ModCandidateImpl> mods = modsById.get(id);
		boolean removed = mods != null && mods.remove(mod) // remove from candidates
				|| addedMods.remove(mod); // remove from pending additions if not already in candidates

		if (removed) {
			// remove parent refs
			for (ModCandidateImpl m : mod.getContainingMods()) {
				m.getContainedMods().remove(mod);
			}

			// remove child refs
			for (ModCandidateImpl m : mod.getContainedMods()) {
				m.getContainingMods().remove(mod);
			}

			// remove from sorted candidate list
			allModsSorted.remove(mod);

			// discard empty by-id refs
			if (mods != null && mods.isEmpty()) modsById.remove(id);
		}

		return removed;
	}
}
