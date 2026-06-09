package com.synapsedb.config;

import com.synapsedb.api.auth.AgentKeyRecord;
import com.synapsedb.core.MemoryConfig;
import jakarta.annotation.PostConstruct;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Loads pre-seeded API keys from {@code {configDir}/api-keys.yml} at startup into an
 * in-memory {@code ConcurrentHashMap<hash, AgentKeyRecord>} and serves auth lookups.
 *
 * <p>File format (the file stores HASHES, never raw keys):
 * <pre>
 * keys:
 *   - agentId: 0
 *     label: seed-agent
 *     keyHash: &lt;sha256hex of the raw key&gt;
 * </pre>
 *
 * <p><b>[D3] Runtime keys.</b> {@link #registerRuntimeKey} PUTs a newly minted key's hash
 * into the SAME map — an in-memory write, never a file write (honors "no runtime writes to
 * the key file"). V1 limitation: runtime keys are lost on restart (cure: {@code T-KEY-PERSIST}).
 */
@Component
public final class ApiKeyConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyConfigLoader.class);
    private static final String KEY_FILE = "api-keys.yml";

    private final Map<String, AgentKeyRecord> byHash = new ConcurrentHashMap<>();
    private final MemoryConfig config;

    public ApiKeyConfigLoader(MemoryConfig config) {
        this.config = config;
    }

    @PostConstruct
    void load() {
        Path file = Path.of(config.configDir(), KEY_FILE);
        if (!Files.isRegularFile(file)) {
            log.info("No {} found at {} — starting with no pre-seeded agents (runtime registration still works).",
                    KEY_FILE, file.toAbsolutePath());
            return;
        }
        int loaded = 0;
        try (InputStream in = Files.newInputStream(file)) {
            // SafeConstructor disables arbitrary type instantiation (!!javatype tags) — the
            // key file is operator-controlled, but unrestricted Yaml is a known gadget footgun.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object root = yaml.load(in);
            for (Map<String, Object> entry : keyEntries(root)) {
                Integer agentId = asInt(entry.get("agentId"));
                String keyHash = asString(entry.get("keyHash"));
                String label = asString(entry.get("label"));
                if (agentId == null || keyHash == null || keyHash.isBlank()) {
                    log.warn("Skipping malformed api-key entry (need agentId + keyHash): {}", entry);
                    continue;
                }
                byHash.put(keyHash, new AgentKeyRecord(agentId, label));
                loaded++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + file.toAbsolutePath(), e);
        }
        log.info("Loaded {} pre-seeded API key(s) from {}", loaded, file.toAbsolutePath());
    }

    /** Look up by RAW key (hashes internally). Empty if unknown. */
    public Optional<AgentKeyRecord> lookupByRawKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHash.get(DigestUtils.sha256Hex(rawKey)));
    }

    /** [D3] Register a runtime-minted raw key in memory only. */
    public void registerRuntimeKey(String rawKey, AgentKeyRecord record) {
        byHash.put(DigestUtils.sha256Hex(rawKey), record);
    }

    /** Agent ids that came from the key file at startup (registered as shards on boot). */
    public Set<Integer> seededAgentIds() {
        return byHash.values().stream().map(AgentKeyRecord::agentId).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keyEntries(Object root) {
        if (root instanceof Map<?, ?> map && map.get("keys") instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private static Integer asInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : null;
    }

    private static String asString(Object o) {
        return (o == null) ? null : o.toString();
    }
}
