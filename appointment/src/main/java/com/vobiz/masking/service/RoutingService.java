package com.vobiz.masking.service;

import com.vobiz.masking.model.Agent;
import com.vobiz.masking.repo.AgentDao;
import com.vobiz.masking.repo.SettingsDao;

import java.util.Comparator;
import java.util.List;

/**
 * Decides which agents a call may be bridged to.
 *
 * Option A (default): ring every active agent at once — first to answer wins.
 * Option B (respect_presence=true): only ring agents marked 'available'.
 * Sequential mode rings them one by one in priority order (chained via /voice/dial-step).
 */
public class RoutingService {

    private final AgentDao agentDao;
    private final SettingsDao settingsDao;

    public RoutingService(AgentDao agentDao, SettingsDao settingsDao) {
        this.agentDao = agentDao;
        this.settingsDao = settingsDao;
    }

    public boolean respectPresence() {
        return settingsDao.getBool("respect_presence", false);
    }

    public List<Agent> eligibleAgents() {
        return selectEligible(agentDao.listActive(), respectPresence());
    }

    /** Pure selection logic, unit-testable without a database. */
    public static List<Agent> selectEligible(List<Agent> activeAgents, boolean respectPresence) {
        return activeAgents.stream()
                .filter(a -> !respectPresence || "available".equals(a.getStatus()))
                .sorted(Comparator.comparingInt(Agent::getPriority).thenComparing(Agent::getId))
                .toList();
    }
}
