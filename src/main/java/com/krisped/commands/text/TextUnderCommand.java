package com.krisped.commands.text;

import com.krisped.KPWebhookPlugin;

/**
 * Handles TEXT_UNDER command - displays text under the player's feet
 */
public class TextUnderCommand
{
    private final KPWebhookPlugin plugin;

    public TextUnderCommand(KPWebhookPlugin plugin)
    {
        this.plugin = plugin;
    }

    /**
     * Execute TEXT_UNDER command with the given text
     * @param text The text to display under the player's feet
     */
    public void execute(String text)
    {
        if (text != null && !text.trim().isEmpty())
        {
            // Use the existing addOverheadText method with "Under" position
            plugin.addOverheadText(null, text.trim(), "Under");
        }
    }

    /**
     * Get the command name
     * @return "TEXT_UNDER"
     */
    public static String getCommandName()
    {
        return "TEXT_UNDER";
    }

    /**
     * Get command description for help text
     * @return Description of the TEXT_UNDER command
     */
    public static String getDescription()
    {
        return "TEXT_UNDER <text>            Text under player feet";
    }
}
