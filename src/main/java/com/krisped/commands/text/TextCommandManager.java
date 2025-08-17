package com.krisped.commands.text;

import com.krisped.KPWebhookPlugin;

/**
 * Manager for text-related commands (TEXT_UNDER only)
 */
public class TextCommandManager
{
    private final TextUnderCommand textUnderCommand;

    public TextCommandManager(KPWebhookPlugin plugin)
    {
        textUnderCommand = new TextUnderCommand(plugin);
    }

    /**
     * Execute a text command
     * @param commandName The command name (TEXT_UNDER)
     * @param text The text to display
     * @return true if command was handled, false if unknown command
     */
    public boolean executeCommand(String commandName, String text)
    {
        if ("TEXT_UNDER".equalsIgnoreCase(commandName))
        {
            textUnderCommand.execute(text);
            return true;
        }
        return false;
    }

    /**
     * Check if a command is a text command
     * @param commandName The command name to check
     * @return true if it's a text command
     */
    public boolean isTextCommand(String commandName)
    {
        return "TEXT_UNDER".equalsIgnoreCase(commandName);
    }

    /**
     * Get all text command descriptions for help text
     * @return Array of command descriptions
     */
    public static String[] getCommandDescriptions()
    {
        return new String[] {
            TextUnderCommand.getDescription()
        };
    }
}
