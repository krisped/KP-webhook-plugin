package com.krisped;

import lombok.*;
import net.runelite.api.Skill;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KPWebhookPreset
{
    public enum TriggerType
    {
        MANUAL,
        STAT,
        WIDGET
    }

    public enum StatMode
    {
        LEVEL_UP,
        ABOVE,
        BELOW
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatConfig
    {
        private Skill skill;
        private StatMode mode;
        private int threshold;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WidgetConfig
    {
        private int groupId;
        private Integer childId;
    }

    @Builder.Default
    private int id = -1;

    private String title;
    private TriggerType triggerType;
    private StatConfig statConfig;
    private WidgetConfig widgetConfig;

    private String webhookUrl;
    @Builder.Default
    private boolean useDefaultWebhook = false;

    private String commands;

    @Builder.Default
    private boolean active = true;

    // Runtime state
    @Builder.Default
    private boolean lastConditionMet = false;
    @Builder.Default
    private int lastSeenRealLevel = -1;

    // =========== Highlight OUTLINE settings ===========
    @Builder.Default
    private Integer hlOutlineDuration = 5;
    @Builder.Default
    private Integer hlOutlineWidth = 4;
    @Builder.Default
    private String  hlOutlineColor = "#FFFF00";
    @Builder.Default
    private Boolean hlOutlineBlink = false;
    @Builder.Default
    private Integer hlOutlineBlinkInterval = 2; // kept for backward compatibility (not used now)

    // =========== Highlight TILE settings ===========
    @Builder.Default
    private Integer hlTileDuration = 5;
    @Builder.Default
    private Integer hlTileWidth = 2;
    @Builder.Default
    private String  hlTileColor = "#00FF88";
    @Builder.Default
    private Boolean hlTileBlink = false;
    @Builder.Default
    private Integer hlTileBlinkInterval = 2;

    // =========== Highlight HULL settings ===========
    @Builder.Default
    private Integer hlHullDuration = 5;
    @Builder.Default
    private Integer hlHullWidth = 2;
    @Builder.Default
    private String  hlHullColor = "#FF55FF";
    @Builder.Default
    private Boolean hlHullBlink = false;
    @Builder.Default
    private Integer hlHullBlinkInterval = 2;

    // =========== Highlight MINIMAP settings ===========
    @Builder.Default
    private Integer hlMinimapDuration = 5;
    @Builder.Default
    private Integer hlMinimapWidth = 2;
    @Builder.Default
    private String  hlMinimapColor = "#00FFFF";
    @Builder.Default
    private Boolean hlMinimapBlink = false;
    @Builder.Default
    private Integer hlMinimapBlinkInterval = 2;

    // =========== Text OVER settings ===========
    @Builder.Default
    private String textOver = "";
    @Builder.Default
    private String textOverColor = "#FFFFFF";
    @Builder.Default
    private Boolean textOverBlink = false;
    @Builder.Default
    private Integer textOverSize = 16;
    @Builder.Default
    private Integer textOverDuration = 80;

    // =========== Text CENTER settings ===========
    @Builder.Default
    private String textCenter = "";
    @Builder.Default
    private String textCenterColor = "#FFFFFF";
    @Builder.Default
    private Boolean textCenterBlink = false;
    @Builder.Default
    private Integer textCenterSize = 16;
    @Builder.Default
    private Integer textCenterDuration = 80;

    // =========== Text UNDER settings ===========
    @Builder.Default
    private String textUnder = "";
    @Builder.Default
    private String textUnderColor = "#FFFFFF";
    @Builder.Default
    private Boolean textUnderBlink = false;
    @Builder.Default
    private Integer textUnderSize = 16;
    @Builder.Default
    private Integer textUnderDuration = 80;

    public String prettyTrigger()
    {
        if (triggerType == null)
        {
            return "?";
        }
        switch (triggerType)
        {
            case MANUAL:
                return "Manual";
            case STAT:
                if (statConfig == null || statConfig.getSkill() == null)
                {
                    return "STAT ?";
                }
                switch (statConfig.getMode())
                {
                    case LEVEL_UP:
                        return statConfig.getSkill().name() + " LEVEL_UP";
                    case ABOVE:
                        return statConfig.getSkill().name() + " > " + statConfig.getThreshold();
                    case BELOW:
                        return statConfig.getSkill().name() + " < " + statConfig.getThreshold();
                }
                return "STAT ?";
            case WIDGET:
                if (widgetConfig == null)
                {
                    return "WIDGET ?";
                }
                if (widgetConfig.getChildId() != null)
                {
                    return "WIDGET " + widgetConfig.getGroupId() + ":" + widgetConfig.getChildId();
                }
                return "WIDGET " + widgetConfig.getGroupId();
            default:
                return "?";
        }
    }
}