package com.krisped;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Storage handler for KPWebhook presets.
 * New format: one Notepad-friendly text file per preset, named after the preset title, extension .txt
 * Content is JSON (readable) containing the KPWebhookPreset object.
 * Legacy format (preset-<id>.json) is migrated automatically.
 */
@Slf4j
public class KPWebhookStorage
{
    private static final String PLUGIN_DIR_NAME = "kp-webhook";
    private static final String PRESETS_DIR_NAME = "presets";
    private static final String LEGACY_PREFIX = "preset-";
    private static final String LEGACY_SUFFIX = ".json";
    private static final String NEW_EXT = ".txt"; // Notepad friendly

    private final ConfigManager configManager;
    private final Gson gson;

    public KPWebhookStorage(ConfigManager configManager, Gson gson)
    {
        this.configManager = configManager;
        this.gson = gson;

        // Log the RuneLite directory location for debugging
        log.info("RuneLite directory: {}", RuneLite.RUNELITE_DIR.getAbsolutePath());
        log.info("Target presets directory will be: {}", dotKpDir().getAbsolutePath());
    }

    private File baseConfigDir()
    {
        // Use RuneLite.RUNELITE_DIR directly since getConfigDirectory() doesn't exist
        return RuneLite.RUNELITE_DIR;
    }

    private File dotKpDir()
    {
        // Use parent directory of RuneLite directory, not inside it
        File runeliteParent = RuneLite.RUNELITE_DIR.getParentFile();
        return new File(runeliteParent, ".kp" + File.separator + PLUGIN_DIR_NAME + File.separator + PRESETS_DIR_NAME);
    }

    private File preferredDir()
    {
        // Always use .kp path as requested by user
        return dotKpDir();
    }

    private void ensureDir()
    {
        File d = preferredDir();
        log.info("Attempting to create directory: {}", d.getAbsolutePath());
        if (!d.exists())
        {
            boolean created = d.mkdirs();
            log.info("Directory creation result: {} for path: {}", created, d.getAbsolutePath());
            if (!created)
            {
                log.warn("Could not create presets directory: {}", d.getAbsolutePath());
            }
        }
        else
        {
            log.info("Directory already exists: {}", d.getAbsolutePath());
        }
    }

    private String sanitize(String title)
    {
        if (title == null || title.isBlank()) return "untitled";
        String s = title.trim();
        // Replace characters not suitable for file names
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        // Limit length to avoid OS issues
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    private File fileForTitle(String title)
    {
        return new File(preferredDir(), sanitize(title) + NEW_EXT);
    }

    public List<KPWebhookPreset> loadAll()
    {
        ensureDir();
        List<KPWebhookPreset> list = new ArrayList<>();
        File dir = preferredDir();
        File[] newFiles = dir.listFiles((d,name) -> name.toLowerCase(Locale.ROOT).endsWith(NEW_EXT));
        if (newFiles != null)
        {
            for (File f : newFiles)
            {
                try {
                    String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    // Migrate old enum names before parsing
                    json = migrateTriggerEnums(json);
                    KPWebhookPreset p = gson.fromJson(json, KPWebhookPreset.class);
                    if (p != null) list.add(p);
                } catch (Exception e) {
                    log.warn("Failed to read preset file {}", f.getName(), e);
                }
            }
        }
        // Legacy migration
        File[] legacy = dir.listFiles((d,name) -> name.startsWith(LEGACY_PREFIX) && name.endsWith(LEGACY_SUFFIX));
        if (legacy != null)
        {
            for (File f : legacy)
            {
                try {
                    String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    json = migrateTriggerEnums(json);
                    KPWebhookPreset p = gson.fromJson(json, KPWebhookPreset.class);
                    if (p != null)
                    {
                        boolean exists = list.stream().anyMatch(x -> x.getId() == p.getId());
                        if (!exists) list.add(p);
                        save(p, null);
                        if (!f.delete()) { /* ignore */ }
                    }
                } catch (Exception e) {
                    log.warn("Failed to migrate legacy preset {}", f.getName(), e);
                }
            }
        }
        log.info("Loaded {} preset(s) from {}", list.size(), dir.getAbsolutePath());
        return list;
    }

    private String migrateTriggerEnums(String json) {
        if (json == null) return null;
        return json.replace("\"triggerType\":\"HIT_SPLAT_SELF\"", "\"triggerType\":\"HITSPLAT_SELF\"")
                .replace("\"triggerType\":\"HIT_SPLAT_TARGET\"", "\"triggerType\":\"HITSPLAT_TARGET\"");
    }

    public void save(KPWebhookPreset preset, String previousTitle)
    {
        log.info("Saving preset: '{}' (ID: {}) to storage", preset.getTitle(), preset.getId());
        ensureDir();

        if (previousTitle != null && !previousTitle.equals(preset.getTitle()))
        {
            // remove old file if name changed
            File oldFile = fileForTitle(previousTitle);
            log.info("Preset title changed from '{}' to '{}', removing old file: {}",
                    previousTitle, preset.getTitle(), oldFile.getAbsolutePath());
            if (oldFile.exists() && !oldFile.delete())
            {
                log.warn("Could not delete old preset file {}", oldFile.getName());
            }
        }

        // Ensure unique filename if title duplicates another preset with different id
        File target = fileForTitle(preset.getTitle());
        log.info("Target file path: {}", target.getAbsolutePath());

        if (target.exists())
        {
            log.info("Target file already exists, checking for ID conflicts...");
            // If file belongs to different preset id, append suffix
            try {
                String json = new String(java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
                KPWebhookPreset existing = gson.fromJson(json, KPWebhookPreset.class);
                if (existing != null && existing.getId() != preset.getId())
                {
                    log.info("ID conflict detected (existing: {}, new: {}), finding alternative filename",
                            existing.getId(), preset.getId());
                    int i=1;
                    while (true)
                    {
                        File alt = new File(preferredDir(), sanitize(preset.getTitle()) + "_"+ i + NEW_EXT);
                        if (!alt.exists()) {
                            target = alt;
                            log.info("Using alternative filename: {}", target.getAbsolutePath());
                            break;
                        }
                        i++;
                    }
                }
            } catch (Exception e) {
                log.warn("Error checking existing file for ID conflict", e);
            }
        }

        try (Writer w = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))
        {
            // Use pretty-printed JSON for better readability in Notepad
            String prettyJson = gson.toJson(preset);
            w.write(prettyJson);
            log.info("Successfully saved preset '{}' to file: {}", preset.getTitle(), target.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to save preset '{}' to file: {}", preset.getTitle(), target.getAbsolutePath(), e);
        }
    }

    public void delete(KPWebhookPreset preset)
    {
        if (preset == null) return;
        File f = fileForTitle(preset.getTitle());
        if (f.exists() && !f.delete())
        {
            log.debug("Could not delete preset file {}", f.getName());
        }
    }
}
