package com.vobiz.masking.repo;

import com.vobiz.masking.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link Agent}. */
public interface AgentRepository extends JpaRepository<Agent, Long> {

    List<Agent> findAllByOrderByPriorityAscIdAsc();

    List<Agent> findByActiveTrueOrderByPriorityAscIdAsc();

    Optional<Agent> findByPhoneE164(String phoneE164);
}
