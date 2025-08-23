package com.krisped;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("kpwebhook")
public interface KPWebhookConfig extends Config
{
    // Discord section
    @ConfigSection(
            name = "Discord",
            description = "Settings for the Discord webhook.",
            position = 0
    )
    String discordSection = "discord";

    @ConfigItem(
            keyName = "defaultWebhookUrl",
            name = "Default Webhook URL",
            description = "Global Discord webhook URL used by presets with 'Use default webhook' enabled.",
            section = discordSection,
            position = 0
    )
    default String defaultWebhookUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "discordTest",
            name = "Test Discord Webhook",
            description = "Toggle ON to send a test message. It will automatically switch OFF after running.",
            section = discordSection,
            position = 1
    )
    default boolean discordTest() { return false; }

    // Home Assistant section
    @ConfigSection(
            name = "Home Assistant",
            description = "Settings for optional Home Assistant integration.",
            position = 5
    )
    String homeAssistantSection = "homeAssistant";

    @ConfigItem(
            keyName = "homeAssistantUrl",
            name = "Base URL",
            description = "Home Assistant base URL (include http(s):// and port if required).",
            section = homeAssistantSection,
            position = 0
    )
    default String homeAssistantUrl() { return ""; }

    @ConfigItem(
            keyName = "homeAssistantApiToken",
            name = "Long-Lived Access Token",
            description = "Home Assistant long-lived access token (stored unencrypted in RuneLite config).",
            section = homeAssistantSection,
            position = 1
    )
    default String homeAssistantApiToken() { return ""; }

    @ConfigItem(
            keyName = "homeAssistantTest",
            name = "Test HA URL/API",
            description = "Toggle ON to call /api with the token. It will automatically switch OFF after running.",
            section = homeAssistantSection,
            position = 2
    )
    default boolean homeAssistantTest() { return false; }

    @ConfigSection(
            name = "Screenshots",
            description = "Screenshot settings.",
            position = 10
    )
    String screenshotSection = "screenshots";

    @ConfigItem(
            keyName = "hideTooltipsInScreenshots",
            name = "Hide tooltips",
            description = "Remove mouse tooltips from plugin screenshots.",
            section = screenshotSection
    )
    default boolean hideTooltipsInScreenshots() { return true; }

    // UI section
    @ConfigSection(
            name = "UI",
            description = "User interface settings.",
            position = 50
    )
    String uiSection = "ui";

    @ConfigItem(
            keyName = "showLastTriggered",
            name = "Show last time triggered",
            description = "Show relative time since each preset last triggered.",
            section = uiSection,
            position = 0
    )
    default boolean showLastTriggered() { return false; }
}