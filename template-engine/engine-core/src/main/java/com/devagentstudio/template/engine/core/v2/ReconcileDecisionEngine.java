package com.devagentstudio.template.engine.core.v2;

import com.devagentstudio.template.engine.source.TemplateSourceException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure V2 decision engine. It never receives a workspace path, content, or digest. */
public final class ReconcileDecisionEngine {
    public enum Mode { APPLY, RECONCILE }

    public UpdateResult decide(TemplateStateV2 current, Map<String, CapabilityState> requested, Mode mode, TemplateRelease release) {
        if (current == null) throw new TemplateSourceException("TEMPLATE_STATE_REQUIRED");
        Map<String, CapabilityState> normalizedRequested = enabledOnly(requested, release);
        Map<String, CapabilityState> targetEffective = resolve(normalizedRequested, release);
        removalGuard(current.effective(), targetEffective);
        boolean additionsNeedCreate = additionStateRequiresChange(current, targetEffective, release);

        List<ReconcileReason> reasons = reasons(current, normalizedRequested, targetEffective, mode, release, additionsNeedCreate);
        if (mode == Mode.RECONCILE && (reasons.contains(ReconcileReason.ENABLE) || reasons.contains(ReconcileReason.CONFIG_CHANGE)
                || reasons.contains(ReconcileReason.RELEASE_REFRESH)))
            throw new TemplateSourceException("RECONCILE_STATE_CHANGE_REQUIRED");
        if (reasons.isEmpty()) return UpdateResult.noChange();

        Set<String> selected = selectedCapabilities(current, targetEffective, reasons, release, additionsNeedCreate);
        List<String> order = executionOrder(selected, targetEffective, release);
        Map<String, AppliedAdditionState> nextAdditions = new LinkedHashMap<String, AppliedAdditionState>(current.appliedAdditions());
        List<ModificationStrategy> strategies = new ArrayList<ModificationStrategy>();
        List<Map<String, Object>> validators = new ArrayList<Map<String, Object>>();

        for (int capabilityRank = 0; capabilityRank < order.size(); capabilityRank++) {
            String id = order.get(capabilityRank);
            CapabilityDefinitionV2 definition = release.capabilities().get(id);
            for (ExistingTargetDefinition target : definition.existingTargets()) {
                StrategyDefinition registered = release.strategies().get(target.strategyId());
                Map<String, Object> parameters = new LinkedHashMap<String, Object>(registered.parameters());
                strategies.add(strategy(registered.type(), target.strategyId(), target.path(), capabilityRank, 1, registered.order(), parameters));
            }
            for (AdditionDefinition addition : definition.additions()) {
                AppliedAdditionState applied = current.appliedAdditions().get(addition.id());
                if (applied == null) {
                    Map<String, Object> parameters = map("capabilityId", id, "sourceRef", addition.source());
                    strategies.add(strategy("ADD_FILE", addition.id(), addition.target(), capabilityRank, 0, 0, parameters));
                    nextAdditions.put(addition.id(), new AppliedAdditionState(id, addition.target(), release.revision()));
                } else {
                    if (!id.equals(applied.capabilityId()) || !addition.target().equals(applied.target()))
                        throw new TemplateSourceException("ADDITION_IDENTITY_CHANGED: " + addition.id());
                    if (addition.maintainPolicy().mode() == MaintainPolicy.Mode.STRATEGY) {
                        StrategyDefinition registered = release.strategies().get(addition.maintainPolicy().strategyId());
                        if (registered == null || !addition.target().equals(registered.target()))
                            throw new TemplateSourceException("CAPABILITY_V2_INVALID: unknown or mismatched maintain strategy");
                        strategies.add(strategy(registered.type(), registered.id(), registered.target(), capabilityRank, 1, registered.order(),
                                new LinkedHashMap<String, Object>(registered.parameters())));
                    }
                }
            }
            for (MigrationDefinition migration : definition.migrations()) {
                strategies.add(strategy("ADD_FILE", "migration." + id + "." + migration.id(), migration.target(), capabilityRank, 0, 0,
                        map("capabilityId", id, "sourceRef", migration.source(), "migrationId", migration.id())));
            }
            validators.addAll(definition.validators());
        }
        Collections.sort(strategies, new Comparator<ModificationStrategy>() {
            @Override public int compare(ModificationStrategy left, ModificationStrategy right) {
                int byCapability = Integer.compare(left.capabilityRank(), right.capabilityRank());
                if (byCapability != 0) return byCapability;
                int byPhase = Integer.compare(left.phase(), right.phase());
                if (byPhase != 0) return byPhase;
                int byOrder = Integer.compare(left.order(), right.order());
                return byOrder != 0 ? byOrder : left.strategyId().compareTo(right.strategyId());
            }
        });
        Set<String> strategyIds = new HashSet<String>();
        for (ModificationStrategy strategy : strategies) if (!strategyIds.add(strategy.strategyId()))
            throw new TemplateSourceException("STRATEGY_ID_DUPLICATE: " + strategy.strategyId());
        return UpdateResult.change(reasons, strategies,
                new TemplateStateV2(release.revision(), normalizedRequested, targetEffective, nextAdditions),
                new ValidationPlan(validators));
    }

    private static List<ReconcileReason> reasons(TemplateStateV2 current, Map<String, CapabilityState> requested,
                                                  Map<String, CapabilityState> effective, Mode mode, TemplateRelease release,
                                                  boolean additionsNeedCreate) {
        List<ReconcileReason> result = new ArrayList<ReconcileReason>();
        for (String id : effective.keySet()) if (!current.effective().containsKey(id)) { result.add(ReconcileReason.ENABLE); break; }
        if (!current.requested().equals(requested) || !current.effective().equals(effective) || additionsNeedCreate) result.add(ReconcileReason.CONFIG_CHANGE);
        if (!current.templateRevision().equals(release.revision())) result.add(ReconcileReason.RELEASE_REFRESH);
        if (mode == Mode.RECONCILE) result.add(ReconcileReason.HEALTH_REPAIR);
        return result;
    }

    private static Set<String> selectedCapabilities(TemplateStateV2 current, Map<String, CapabilityState> effective,
                                                     List<ReconcileReason> reasons, TemplateRelease release, boolean additionsNeedCreate) {
        boolean all = reasons.contains(ReconcileReason.RELEASE_REFRESH) || reasons.contains(ReconcileReason.HEALTH_REPAIR);
        Set<String> selected = new LinkedHashSet<String>();
        for (String id : effective.keySet()) if (all || !current.effective().containsKey(id)
                || !effective.get(id).equals(current.effective().get(id)) || (additionsNeedCreate && missingAddition(current, release.capabilities().get(id)))) selected.add(id);
        return selected;
    }

    private static boolean additionStateRequiresChange(TemplateStateV2 current, Map<String, CapabilityState> effective,
                                                       TemplateRelease release) {
        for (String id : effective.keySet()) {
            CapabilityDefinitionV2 definition = release.capabilities().get(id);
            for (AdditionDefinition addition : definition.additions()) {
                AppliedAdditionState applied = current.appliedAdditions().get(addition.id());
                if (applied == null) return true;
                if (!id.equals(applied.capabilityId()) || !addition.target().equals(applied.target()))
                    throw new TemplateSourceException("ADDITION_IDENTITY_CHANGED: " + addition.id());
            }
        }
        return false;
    }
    private static boolean missingAddition(TemplateStateV2 current, CapabilityDefinitionV2 definition) {
        for (AdditionDefinition addition : definition.additions()) if (!current.appliedAdditions().containsKey(addition.id())) return true;
        return false;
    }

    private static Map<String, CapabilityState> enabledOnly(Map<String, CapabilityState> requested, TemplateRelease release) {
        Map<String, CapabilityState> result = new LinkedHashMap<String, CapabilityState>();
        List<String> ids = new ArrayList<String>(requested.keySet()); Collections.sort(ids);
        for (String id : ids) {
            CapabilityDefinitionV2 definition = release.capabilities().get(id);
            if (definition == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id);
            CapabilityState state = requested.get(id);
            if (state.enabled()) result.put(id, state);
        }
        return result;
    }

    private static Map<String, CapabilityState> resolve(Map<String, CapabilityState> requested, TemplateRelease release) {
        Map<String, CapabilityState> result = new LinkedHashMap<String, CapabilityState>();
        List<String> ids = new ArrayList<String>(requested.keySet()); Collections.sort(ids);
        Set<String> visiting = new HashSet<String>();
        for (String id : ids) resolve(id, requested, release, result, visiting);
        return result;
    }
    private static void resolve(String id, Map<String, CapabilityState> requested, TemplateRelease release,
                                Map<String, CapabilityState> effective, Set<String> visiting) {
        if (effective.containsKey(id)) return;
        if (!visiting.add(id)) throw new TemplateSourceException("CAPABILITY_CYCLE: " + id);
        CapabilityDefinitionV2 definition = release.capabilities().get(id);
        if (definition == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id);
        for (String dependency : definition.requires()) resolve(dependency, requested, release, effective, visiting);
        CapabilityState explicit = requested.get(id);
        effective.put(id, explicit == null ? new CapabilityState(true, definition.defaultConfig()) : explicit);
        visiting.remove(id);
    }
    private static void removalGuard(Map<String, CapabilityState> current, Map<String, CapabilityState> target) {
        for (String id : current.keySet()) if (!target.containsKey(id)) throw new TemplateSourceException("CAPABILITY_REMOVAL_UNSUPPORTED: " + id);
    }
    private static List<String> executionOrder(Set<String> selected, Map<String, CapabilityState> effective, TemplateRelease release) {
        List<String> result = new ArrayList<String>(); Set<String> visited = new HashSet<String>();
        List<String> ids = new ArrayList<String>(selected); Collections.sort(ids);
        for (String id : ids) ordered(id, selected, effective, release, visited, result);
        return result;
    }
    private static void ordered(String id, Set<String> selected, Map<String, CapabilityState> effective, TemplateRelease release,
                                Set<String> visited, List<String> result) {
        if (!visited.add(id)) return;
        for (String dependency : release.capabilities().get(id).requires()) if (effective.containsKey(dependency) && selected.contains(dependency))
            ordered(dependency, selected, effective, release, visited, result);
        result.add(id);
    }
    private static ModificationStrategy strategy(String type, String id, String target, int capabilityRank, int phase, int order,
                                                 Map<String, Object> parameters) {
        return new ModificationStrategy(type, id, target, capabilityRank, phase, order, parameters);
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
