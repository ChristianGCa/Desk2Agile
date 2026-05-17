package com.chris.glpi_taiga_integration.service;

import com.chris.glpi_taiga_integration.config.GlpiEntitiesProperties;
import com.chris.glpi_taiga_integration.config.TaigaRoutingProperties;
import com.chris.glpi_taiga_integration.dto.GlpiEntityResponse;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProjectRoutingService {

    private static final String DEFAULT_FALLBACK_SLUG = "diversos";

    private static final Logger log = LoggerFactory.getLogger(ProjectRoutingService.class);

    private final TaigaRoutingProperties taigaRoutingProperties;
    private final GlpiEntitiesProperties glpiEntitiesProperties;
    private final GlpiIntegrationService glpiIntegrationService;

    public ProjectRoutingService(
            TaigaRoutingProperties taigaRoutingProperties,
            GlpiEntitiesProperties glpiEntitiesProperties,
            GlpiIntegrationService glpiIntegrationService) {
        this.taigaRoutingProperties = taigaRoutingProperties;
        this.glpiEntitiesProperties = glpiEntitiesProperties;
        this.glpiIntegrationService = glpiIntegrationService;
    }

    public String resolveTaigaProjectSlugForTicket(Long ticketId, String sessionToken) {
        String fallback = toTaigaSlug(taigaRoutingProperties.getFallbackProjectName());

        if (fallback.isBlank()) {
            fallback = DEFAULT_FALLBACK_SLUG;
            log.warn("ROUTING - Fallback inválido/vazio. Usando fallback padrão='{}'.", fallback);
        }
        var mappings = taigaRoutingProperties.getEntityMappings();
        if (mappings == null || mappings.isEmpty()) {
            log.warn("ROUTING - Nenhum mapeamento configurado. Usando fallback='{}'.", fallback);
            return fallback;
        }

        Optional<Long> entityIdOpt = glpiIntegrationService.getTicketEntityId(ticketId, sessionToken);
        if (entityIdOpt.isEmpty()) {
            log.warn("ROUTING - Ticket {} sem entities_id. Usando fallback='{}'.", ticketId, fallback);
            return fallback;
        }

        Long entityId = entityIdOpt.get();
        Optional<GlpiEntityResponse> entityOpt = glpiIntegrationService.getEntityById(entityId, sessionToken);
        if (entityOpt.isEmpty()) {
            log.warn("ROUTING - Não foi possível carregar Entity id={}. Usando fallback='{}'.", entityId, fallback);
            return fallback;
        }

        GlpiEntityResponse entity = entityOpt.get();
        String entityName = entity.name() != null ? entity.name().trim() : "";

        if (entityName.isBlank()) {
            log.warn("ROUTING - Entity id={} sem nome. Usando fallback='{}'.", entityId, fallback);
            return fallback;
        }

        if (entityName.equalsIgnoreCase(glpiEntitiesProperties.getRootName())) {
            log.info("ROUTING - Ticket {} está na entidade raiz ('{}'). Usando fallback='{}'.",
                    ticketId, entityName, fallback);
            return fallback;
        }

        String mapped = resolveFromMappings(mappings, entityName);
        if (mapped == null || mapped.isBlank()) {
            log.warn("ROUTING - Entidade '{}' não mapeada. Usando fallback='{}'.", entityName, fallback);
            return fallback;
        }

        log.info("ROUTING - Ticket {}: entidade '{}' -> projeto slug='{}'.", ticketId, entityName, mapped);
        return mapped;
    }

    /**
     * Gera o slug do projeto Taiga a partir do nome do projeto, seguindo a
     * mesma lógica do Taiga: 1. Normaliza e remove acentos/diacríticos (NFD) 2.
     * Converte para minúsculas 3. Remove caracteres que não sejam letras,
     * números, espaços ou hífens 4. Substitui espaços por hífens
     */
    static String toTaigaSlug(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }

    private static String resolveFromMappings(
            List<TaigaRoutingProperties.EntityMapping> mappings,
            String entityName) {
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        for (var m : mappings) {
            if (m == null) {
                continue;
            }
            String glpiName = m.getGlpiEntityName() != null ? m.getGlpiEntityName().trim() : "";
            String projectName = m.getTaigaProjectName() != null ? m.getTaigaProjectName().trim() : "";
            if (glpiName.isBlank() || projectName.isBlank()) {
                continue;
            }
            if (glpiName.equals(entityName)) {
                return toTaigaSlug(projectName);
            }
        }
        return null;
    }
}
