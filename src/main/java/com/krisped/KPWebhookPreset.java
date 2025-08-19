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
        WIDGET,
        PLAYER_SPAWN,
        PLAYER_DESPAWN,
        NPC_SPAWN,
        NPC_DESPAWN,
        ANIMATION_SELF,
        ANIMATION_TARGET,
        ANIMATION_ANY,
        GRAPHIC_SELF,
        GRAPHIC_TARGET,
        GRAPHIC_ANY,
        HITSPLAT_SELF,
        HITSPLAT_TARGET,
        MESSAGE,
        VARBIT,
        VARPLAYER,
        TICK,
        TARGET,
        PROJECTILE_SELF, // new
        PROJECTILE_TARGET, // new
        PROJECTILE_ANY // new
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

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlayerConfig
    {
        @Builder.Default
        private boolean all = true;
        private String name; // case-insensitive match if not null/blank
        private Integer combatRange; // +/- combat levels from local player if not null
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnimationConfig
    {
        private Integer animationId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GraphicConfig
    {
        private Integer graphicId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HitsplatConfig {
        public enum Mode { GREATER, GREATER_EQUAL, EQUAL, LESS_EQUAL, LESS }
        @Builder.Default
        private Mode mode = Mode.GREATER;
        private Integer value; // threshold value
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MessageConfig
    {
        private Integer messageId; // ChatMessageType ID
        private String messageText; // Text to match (supports wildcards like *LEVEL*)
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VarbitConfig
    {
        private Integer varbitId;
        private Integer value;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VarplayerConfig
    {
        private Integer varplayerId;
        private Integer value;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NpcConfig {
        private String rawList; // original comma separated list
        private java.util.List<Integer> npcIds; // parsed numeric ids
        private java.util.List<String> npcNames; // lower-case underscore names
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProjectileConfig {
        private Integer projectileId; // id to match (required)
    }

    @Builder.Default
    private int id = -1;

    private String title;
    private String category; // new optional category label
    private TriggerType triggerType;
    private StatConfig statConfig;
    private WidgetConfig widgetConfig;
    private PlayerConfig playerConfig;
    private AnimationConfig animationConfig;
    private GraphicConfig graphicConfig; // new graphic config
    private HitsplatConfig hitsplatConfig; // new hitsplat config
    private MessageConfig messageConfig;
    private VarbitConfig varbitConfig;
    private VarplayerConfig varplayerConfig;
    private NpcConfig npcConfig; // new optional npc config
    private ProjectileConfig projectileConfig; // new projectile config

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
    @Builder.Default
    private int lastTriggeredBoosted = Integer.MIN_VALUE; // new: last boosted value that triggered while condition true

    // =========== Highlight OUTLINE settings ===========
    @Builder.Default
    private Integer hlOutlineDuration = 5;
    @Builder.Default
    private Integer hlOutlineWidth = 2; // changed from 4 to 2 for thinner default outline
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

    // =========== Highlight SCREEN settings (new) ===========
    @Builder.Default
    private Integer hlScreenDuration = 5;
    @Builder.Default
    private Integer hlScreenWidth = 4; // border thickness
    @Builder.Default
    private String  hlScreenColor = "#FF0000"; // default warning red
    @Builder.Default
    private Boolean hlScreenBlink = false;
    @Builder.Default
    private Integer hlScreenBlinkInterval = 2;

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
    @Builder.Default
    private Boolean textOverBold = false;
    @Builder.Default
    private Boolean textOverItalic = false;
    @Builder.Default
    private Boolean textOverUnderline = false;

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
    @Builder.Default
    private Boolean textCenterBold = false;
    @Builder.Default
    private Boolean textCenterItalic = false;
    @Builder.Default
    private Boolean textCenterUnderline = false;

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
    @Builder.Default
    private Boolean textUnderBold = false;
    @Builder.Default
    private Boolean textUnderItalic = false;
    @Builder.Default
    private Boolean textUnderUnderline = false;

    // =========== Overlay TEXT (OVERLAY_TEXT) settings ===========
    @Builder.Default
    private Integer overlayTextDuration = 100; // ticks
    @Builder.Default
    private String overlayTextColor = "#FFFFFF";
    @Builder.Default
    private Integer overlayTextSize = 16;

    // =========== InfoBox ICON settings ===========
    @Builder.Default
    private Integer infoboxDuration = 100; // ticks
    @Builder.Default
    private Boolean infoboxBlink = false; // toggle icon visibility each tick
    @Builder.Default
    private String infoboxColor = "#FFFFFF"; // optional border / future use

    // =========== IMG settings (simple duration) ==========
    @Builder.Default
    private Integer imgDuration = 100; // ticks
    @Builder.Default
    private Boolean imgBlink = false; // blink overhead images each tick when true

    public String prettyTrigger()
    {
        if (triggerType == null) return "?";
        switch (triggerType) {
            case MANUAL: return "Manual";
            case TARGET: return "TICK (continuous)";
            case TICK: return "TICK (continuous)";
            case STAT:
                if (statConfig == null || statConfig.getSkill()==null) return "STAT ?";
                switch (statConfig.getMode()) {
                    case LEVEL_UP: return statConfig.getSkill().name()+" LEVEL_UP";
                    case ABOVE: return statConfig.getSkill().name()+" > "+statConfig.getThreshold();
                    case BELOW: return statConfig.getSkill().name()+" < "+statConfig.getThreshold();
                }
                return "STAT ?";
            case WIDGET:
                if (widgetConfig==null) return "WIDGET ?";
                return widgetConfig.getChildId()!=null? "WIDGET "+widgetConfig.getGroupId()+":"+widgetConfig.getChildId() : "WIDGET "+widgetConfig.getGroupId();
            case PLAYER_SPAWN:
            case PLAYER_DESPAWN:
                if (playerConfig==null) return (triggerType==TriggerType.PLAYER_SPAWN?"PLAYER_SPAWN":"PLAYER_DESPAWN")+" ?";
                if (playerConfig.isAll()) return (triggerType==TriggerType.PLAYER_SPAWN?"PLAYER_SPAWN":"PLAYER_DESPAWN")+" ALL";
                if (playerConfig.getName()!=null && !playerConfig.getName().isBlank()) return (triggerType==TriggerType.PLAYER_SPAWN?"PLAYER_SPAWN ":"PLAYER_DESPAWN ")+playerConfig.getName();
                if (playerConfig.getCombatRange()!=null) return (triggerType==TriggerType.PLAYER_SPAWN?"PLAYER_SPAWN +/-":"PLAYER_DESPAWN +/-")+playerConfig.getCombatRange();
                return (triggerType==TriggerType.PLAYER_SPAWN?"PLAYER_SPAWN":"PLAYER_DESPAWN")+" ?";
            case NPC_SPAWN:
            case NPC_DESPAWN:
                if (npcConfig==null || ((npcConfig.getNpcIds()==null || npcConfig.getNpcIds().isEmpty()) && (npcConfig.getNpcNames()==null || npcConfig.getNpcNames().isEmpty())))
                    return (triggerType==TriggerType.NPC_SPAWN?"NPC_SPAWN":"NPC_DESPAWN")+" ?";
                String raw = npcConfig.getRawList()!=null? npcConfig.getRawList():"";
                if (raw.length()>30) raw = raw.substring(0,27)+"...";
                return (triggerType==TriggerType.NPC_SPAWN?"NPC_SPAWN ":"NPC_DESPAWN ")+raw;
            case ANIMATION_SELF:
                if (animationConfig==null || animationConfig.getAnimationId()==null) return "ANIMATION_SELF ?";
                return "ANIMATION_SELF "+animationConfig.getAnimationId();
            case ANIMATION_TARGET:
                if (animationConfig==null || animationConfig.getAnimationId()==null) return "ANIMATION_TARGET ?";
                return "ANIMATION_TARGET "+animationConfig.getAnimationId();
            case ANIMATION_ANY: return "ANIMATION_ANY (alle animasjoner)";
            case GRAPHIC_SELF:
                if (graphicConfig==null || graphicConfig.getGraphicId()==null) return "GRAPHIC_SELF ?"; return "GRAPHIC_SELF "+graphicConfig.getGraphicId();
            case GRAPHIC_TARGET:
                if (graphicConfig==null || graphicConfig.getGraphicId()==null) return "GRAPHIC_TARGET ?"; return "GRAPHIC_TARGET "+graphicConfig.getGraphicId();
            case GRAPHIC_ANY: return "GRAPHIC_ANY (alle graphics)";
            case PROJECTILE_SELF:
                if (projectileConfig==null || projectileConfig.getProjectileId()==null) return "PROJECTILE_SELF ?"; return "PROJECTILE_SELF "+projectileConfig.getProjectileId();
            case PROJECTILE_TARGET:
                if (projectileConfig==null || projectileConfig.getProjectileId()==null) return "PROJECTILE_TARGET ?"; return "PROJECTILE_TARGET "+projectileConfig.getProjectileId();
            case PROJECTILE_ANY:
                if (projectileConfig==null || projectileConfig.getProjectileId()==null) return "PROJECTILE_ANY ?";
                return "PROJECTILE_ANY "+projectileConfig.getProjectileId();
            case HITSPLAT_SELF:
                if (hitsplatConfig==null || hitsplatConfig.getValue()==null) return "HITSPLAT_SELF ?";
                return "HITSPLAT_SELF "+humanHitsplatMode(hitsplatConfig.getMode())+hitsplatConfig.getValue();
            case HITSPLAT_TARGET:
                if (hitsplatConfig==null || hitsplatConfig.getValue()==null) return "HITSPLAT_TARGET ?";
                return "HITSPLAT_TARGET "+humanHitsplatMode(hitsplatConfig.getMode())+hitsplatConfig.getValue();
            case MESSAGE:
                if (messageConfig==null) return "MESSAGE ?";
                String res = "MESSAGE";
                if (messageConfig.getMessageId()!=null) res += " "+messageConfig.getMessageId();
                if (messageConfig.getMessageText()!=null && !messageConfig.getMessageText().isBlank()) res += " "+messageConfig.getMessageText();
                return res;
            case VARBIT:
                if (varbitConfig==null) return "VARBIT ?";
                String vb = "VARBIT";
                if (varbitConfig.getVarbitId()!=null) vb += " "+varbitConfig.getVarbitId();
                if (varbitConfig.getValue()!=null) vb += " = "+varbitConfig.getValue();
                return vb;
            case VARPLAYER:
                if (varplayerConfig==null) return "VARPLAYER ?";
                String vp = "VARPLAYER";
                if (varplayerConfig.getVarplayerId()!=null) vp += " "+varplayerConfig.getVarplayerId();
                if (varplayerConfig.getValue()!=null) vp += " = "+varplayerConfig.getValue();
                return vp;
            default: return "?";
        }
    }

    private static String modeSymbol(HitsplatConfig.Mode m) {
        if (m == null) return "?";
        switch (m) {
            case GREATER: return ">";
            case GREATER_EQUAL: return ">=";
            case EQUAL: return "=";
            case LESS_EQUAL: return "<=";
            case LESS: return "<";
        }
        return "?";
    }
    private static String humanHitsplatMode(HitsplatConfig.Mode m) {
        if (m == null) return "";
        switch (m) {
            case LESS:
            case LESS_EQUAL:
                return "BELOW ";
            case EQUAL:
                return "= "; // legacy / rarely used
            case GREATER:
            case GREATER_EQUAL:
            default:
                return "ABOVE ";
        }
    }
}
