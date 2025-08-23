package com.krisped;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Data;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * External (non-Runelite config) user settings stored in ~/.kp/kp-webhook/<file>.json
 * Provides a place for UI-only preferences that should persist even if RL config is wiped.
 * Migrates transparently from the legacy KPWebhookSettings.json file and prior location.
 */
@Data
public class KPWebhookUserSettings {
    private static final String DIR_NAME = ".kp";
    private static final String SUBDIR_NAME = "kp-webhook"; // new subdirectory per user request
    // New (preferred) filename. Keep stable going forward.
    private static final String NEW_FILE_NAME = "KPWebhookPluginSettings.json";
    // Legacy filename kept for backward compatibility migration.
    private static final String LEGACY_FILE_NAME = "KPWebhookSettings.json";

    private boolean showLastTriggered = false; // default
    private int lastTriggeredRefreshSeconds = 60; // configurable future use
    // Persist category expanded/collapsed states (key -> expanded?)
    private Map<String, Boolean> categoryExpandedStates = new HashMap<>();
    // New: ensure legacy config -> external migration for showLastTriggered only happens once
    private boolean migratedShowLastTriggered = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static KPWebhookUserSettings load() {
        // Preferred new path: ~/.kp/kp-webhook/NEW_FILE_NAME
        File preferredFile = getFile(NEW_FILE_NAME);
        // Legacy locations (without subdir) to migrate from if present
        File legacyDir = new File(System.getProperty("user.home", "."), DIR_NAME);
        File legacyNewName = new File(legacyDir, NEW_FILE_NAME); // old location same filename
        File legacyOldName = new File(legacyDir, LEGACY_FILE_NAME); // very old name

        File fileToUse;
        if (preferredFile.exists()) fileToUse = preferredFile;
        else if (legacyNewName.exists()) fileToUse = legacyNewName;
        else if (legacyOldName.exists()) fileToUse = legacyOldName;
        else fileToUse = preferredFile; // none exist yet, will create later

        if (!fileToUse.exists()) {
            return new KPWebhookUserSettings();
        }
        try (Reader r = new BufferedReader(new FileReader(fileToUse))) {
            KPWebhookUserSettings s = GSON.fromJson(r, KPWebhookUserSettings.class);
            if (s == null) s = new KPWebhookUserSettings();
            if (s.lastTriggeredRefreshSeconds < 5) s.lastTriggeredRefreshSeconds = 60;
            // If loaded from a legacy location, persist immediately to new preferred path
            if (!preferredFile.equals(fileToUse)) {
                s.save();
            }
            return s;
        } catch (Exception e) {
            return new KPWebhookUserSettings();
        }
    }

    public void save() {
        File file = getFile(NEW_FILE_NAME); // Always save to new canonical file in subdir.
        File dir = file.getParentFile();
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        try (Writer w = new BufferedWriter(new FileWriter(file))) {
            GSON.toJson(this, w);
        } catch (Exception ignored) {
        }
    }

    private static File getFile(String name) {
        String home = System.getProperty("user.home", ".");
        File base = new File(home, DIR_NAME);
        File sub = new File(base, SUBDIR_NAME);
        return new File(sub, name);
    }
}
