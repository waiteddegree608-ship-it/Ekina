package com.ekina.application.contact;

import com.ekina.application.agentreg.AgentManifest;
import com.ekina.application.agentreg.AgentRegistry;
import com.ekina.application.agentreg.CapabilityPackCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compatibility facade over {@link AgentRegistry} for IM / chat code paths.
 */
@Service
public class ContactRegistry {

    public static final String EKINA = AgentRegistry.EKINA;
    public static final String AGENT = AgentRegistry.AGENT;
    public static final String THREAD_EKINA = AgentRegistry.THREAD_EKINA;
    public static final String THREAD_AGENT = AgentRegistry.THREAD_AGENT;
    public static final String PROFILE_LIFE = CapabilityPackCatalog.LIFE;
    public static final String PROFILE_EXECUTOR = CapabilityPackCatalog.EXECUTOR;

    /** @deprecated use AgentRegistry.toolWhitelist */
    public static final Set<String> EXECUTOR_TOOLS = CapabilityPackCatalog.tools(CapabilityPackCatalog.EXECUTOR);

    @Autowired
    private AgentRegistry agentRegistry;

    public Optional<ContactRecord> find(String contactId) {
        return agentRegistry.find(contactId).map(this::toContact);
    }

    public ContactRecord require(String contactId) {
        return toContact(agentRegistry.require(contactId));
    }

    public ContactRecord resolve(String contactId, String threadId) {
        return toContact(agentRegistry.resolve(contactId, threadId));
    }

    public String threadFor(String contactId) {
        return agentRegistry.require(contactId).getThreadId();
    }

    public boolean isExecutor(String contactId) {
        return agentRegistry.isExecutorFlavor(contactId);
    }

    public boolean isLife(String contactId) {
        return agentRegistry.isLife(contactId);
    }

    public boolean isEnabled(String contactId) {
        return agentRegistry.isEnabled(contactId);
    }

    public List<Map<String, Object>> listForApi(String userId) {
        return agentRegistry.listForApi(userId, true);
    }

    public List<Map<String, Object>> listAllForApi(String userId) {
        return agentRegistry.listForApi(userId, false);
    }

    public void bindSession(String contactId) {
        agentRegistry.bindSession(contactId);
    }

    public Set<String> toolWhitelist(String contactId) {
        return agentRegistry.toolWhitelist(contactId);
    }

    private ContactRecord toContact(AgentManifest m) {
        String profile = m.getPersonaProfile() != null ? m.getPersonaProfile() : PROFILE_EXECUTOR;
        if (m.getCapabilityPacks() != null && m.getCapabilityPacks().contains(CapabilityPackCatalog.LIFE)) {
            profile = PROFILE_LIFE;
        } else if (m.getCapabilityPacks() != null && m.getCapabilityPacks().contains(CapabilityPackCatalog.EXECUTOR)) {
            profile = PROFILE_EXECUTOR;
        }
        ContactRecord c = new ContactRecord(
                m.getId(),
                m.getDisplayName(),
                m.getSignature(),
                m.getThreadId(),
                m.getPersonaProfile(),
                profile,
                m.getAvatarUrl()
        );
        c.setLastPreview(m.getLastPreview());
        c.setLastTs(m.getLastTs());
        return c;
    }
}
