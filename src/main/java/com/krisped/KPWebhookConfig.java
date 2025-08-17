package com.krisped;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

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
}