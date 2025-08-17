package com.krisped;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("kpwebhook")
public interface KPWebhookConfig extends Config
{
    @ConfigItem(
            keyName = "defaultWebhookUrl",
            name = "Default Webhook URL",
            description = "Standard (global) webhook URL som kan brukes av presets som har 'Use standard webhook' aktivert."
    )
    default String defaultWebhookUrl()
    {
        return "";
    }

    @ConfigSection(
            name = "Screenshots",
            description = "Innstillinger for skjermbilder",
            position = 10
    )
    String screenshotSection = "screenshots";

    @ConfigItem(
            keyName = "hideTooltipsInScreenshots",
            name = "Skjul tooltips",
            description = "Fjern muse-tooltip fra skjermbilder tatt av plugin.",
            section = screenshotSection
    )
    default boolean hideTooltipsInScreenshots() { return true; }
}