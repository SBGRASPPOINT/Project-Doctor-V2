package com.vobiz.masking;

import com.vobiz.masking.model.Agent;
import com.vobiz.masking.service.RoutingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingServiceTest {

    private static Agent agent(long id, int priority, String status) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("A" + id);
        a.setPhoneE164("+1555000" + id);
        a.setPriority(priority);
        a.setStatus(status);
        a.setActive(true);
        return a;
    }

    @Test
    void withoutPresenceAllActiveAgentsAreEligibleInPriorityOrder() {
        List<Agent> agents = List.of(
                agent(3, 5, "busy"),
                agent(1, 0, "available"),
                agent(2, 3, "offline"));
        List<Agent> out = RoutingService.selectEligible(agents, false);
        assertEquals(List.of(1L, 2L, 3L), out.stream().map(Agent::getId).toList());
    }

    @Test
    void withPresenceOnlyAvailableAgentsAreEligible() {
        List<Agent> agents = List.of(
                agent(3, 5, "busy"),
                agent(1, 0, "available"),
                agent(2, 3, "offline"),
                agent(4, 1, "available"));
        List<Agent> out = RoutingService.selectEligible(agents, true);
        assertEquals(List.of(1L, 4L), out.stream().map(Agent::getId).toList());
    }
}
