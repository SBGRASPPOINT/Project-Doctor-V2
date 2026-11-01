package com.vobiz.masking.repo;

import com.vobiz.masking.model.Agent;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Agent store, backed by Spring Data JPA ({@link AgentRepository}).
 * Same method signatures as before, so the service layer is unchanged.
 */
public class AgentDao {

    private final AgentRepository repo;

    public AgentDao(AgentRepository repo) {
        this.repo = repo;
    }

    public List<Agent> listAll() {
        return repo.findAllByOrderByPriorityAscIdAsc();
    }

    public List<Agent> listActive() {
        return repo.findByActiveTrueOrderByPriorityAscIdAsc();
    }

    public Optional<Agent> findById(long id) {
        return repo.findById(id);
    }

    public Optional<Agent> findByPhone(String phone) {
        return repo.findByPhoneE164(phone);
    }

    @Transactional
    public Agent create(String name, String phone, int priority) {
        Agent a = new Agent();
        a.setName(name);
        a.setPhoneE164(phone);
        a.setPriority(priority);
        a.setStatus("available");
        a.setActive(true);
        return repo.save(a);
    }

    /** Dynamic partial update; null arguments are left unchanged. Returns false if the agent does not exist. */
    @Transactional
    public boolean update(long id, String name, String phone, Integer priority, String status, Boolean active) {
        return repo.findById(id).map(a -> {
            if (name != null) a.setName(name);
            if (phone != null) a.setPhoneE164(phone);
            if (priority != null) a.setPriority(priority);
            if (status != null) a.setStatus(status);
            if (active != null) a.setActive(active);
            repo.save(a);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean setStatus(long id, String status) {
        return repo.findById(id).map(a -> {
            a.setStatus(status);
            repo.save(a);
            return true;
        }).orElse(false);
    }

    /** Soft delete: the number leaves the rotation but history is kept. */
    @Transactional
    public boolean deactivate(long id) {
        return repo.findById(id).map(a -> {
            a.setActive(false);
            repo.save(a);
            return true;
        }).orElse(false);
    }
}
