package com.emoteorganizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(EmoteOrganizerConfig.GROUP)
public interface EmoteOrganizerConfig extends Config
{
	String GROUP = "emoteorganizer";

	@ConfigSection(
		name = "Sorting",
		description = "Emote sorting options",
		position = 0
	)
	String sortingSection = "sorting";

	@ConfigSection(
		name = "Favorites",
		description = "Favorite emote options",
		position = 1
	)
	String favoritesSection = "favorites";

	@ConfigItem(
		keyName = "sortMode",
		name = "Sort Mode",
		description = "How to sort emotes in the tab",
		section = sortingSection,
		position = 0
	)
	default EmoteSortMode sortMode()
	{
		return EmoteSortMode.ALPHABETICAL;
	}

	@ConfigItem(
		keyName = "showFavoritesOnly",
		name = "Show Favorites Only",
		description = "Only show favorite emotes in the emote tab",
		section = favoritesSection,
		position = 0
	)
	default boolean showFavoritesOnly()
	{
		return false;
	}
}
