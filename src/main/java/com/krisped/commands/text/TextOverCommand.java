package com.krisped.commands.text;

import com.krisped.KPWebhookPlugin;

/**
 * Handles TEXT_OVER command - displays text above the player
 */
public class TextOverCommand
{
    private final KPWebhookPlugin plugin;

    public TextOverCommand(KPWebhookPlugin plugin)
    {
        this.plugin = plugin;
    }

    /**
     * Execute TEXT_OVER command with the given text
     * @param text The text to display above the player
     */
    public void execute(String text)
    {
        if (text != null && !text.trim().isEmpty())
        {
            // Use the existing addOverheadText method with "Above" position
            plugin.addOverheadText(null, text.trim(), "Above");
        }
    }

    /**
     * Get the command name
     * @return "TEXT_OVER"
     */
    public static String getCommandName()
    {
        return "TEXT_OVER";
    }

    /**
     * Get command description for help text
     * @return Description of the TEXT_OVER command
     */
    public static String getDescription()
    {
        return "TEXT_OVER <text>             Text above player";
    }
}
