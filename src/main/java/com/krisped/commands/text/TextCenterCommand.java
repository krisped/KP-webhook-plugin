package com.krisped.commands.text;

import com.krisped.KPWebhookPlugin;

/**
 * Handles TEXT_CENTER command - displays text centered on the player
 */
public class TextCenterCommand
{
    private final KPWebhookPlugin plugin;

    public TextCenterCommand(KPWebhookPlugin plugin)
    {
        this.plugin = plugin;
    }

    /**
     * Execute TEXT_CENTER command with the given text
     * @param text The text to display centered on the player
     */
    public void execute(String text)
    {
        if (text != null && !text.trim().isEmpty())
        {
            // Use the existing addOverheadText method with "Center" position
            plugin.addOverheadText(null, text.trim(), "Center");
        }
    }

    /**
     * Get the command name
     * @return "TEXT_CENTER"
     */
    public static String getCommandName()
    {
        return "TEXT_CENTER";
    }

    /**
     * Get command description for help text
     * @return Description of the TEXT_CENTER command
     */
    public static String getDescription()
    {
        return "TEXT_CENTER <text>           Text centered on player";
    }
}
