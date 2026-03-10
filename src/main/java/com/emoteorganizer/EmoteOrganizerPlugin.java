/*
 * Copyright (c) 2024, EmoteOrganizer
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.emoteorganizer;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptEvent;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import static net.runelite.api.widgets.WidgetConfig.transmitAction;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Emote Favorites",
	description = "Favorite and sort emotes in the emote tab",
	tags = {"emote", "favorites", "favourites", "sort"}
)
public class EmoteOrganizerPlugin extends Plugin
{
	private static final String CONFIG_FAVORITES = "favorites";

	private static final int FAVORITE_OP = 2;
	private static final int DEFAULT_EMOTE_WIDTH = 42;
	private static final int DEFAULT_EMOTE_HEIGHT = 48;
	private static final int DEFAULT_CONTAINER_WIDTH = 190;
	private static final int UI_REFRESH_TICKS = 8;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EmoteOrganizerConfig config;

	@Inject
	private ConfigManager configManager;

	private boolean uiDirty;
	private int pendingUiRefreshTicks;

	@Override
	protected void startUp()
	{
		scheduleUiRefresh(UI_REFRESH_TICKS);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::applyUiIfOpen);
		}
	}

	@Override
	protected void shutDown()
	{
		clientThread.invokeLater(() ->
		{
			teardownUi();
			redrawEmotes();
		});
	}

	@Provides
	EmoteOrganizerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EmoteOrganizerConfig.class);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		if (widgetLoaded.getGroupId() != InterfaceID.EMOTE)
		{
			return;
		}

		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick)
	{
		if ((!uiDirty && pendingUiRefreshTicks <= 0) || !isEmoteTabOpen())
		{
			return;
		}

		if (applyUi())
		{
			uiDirty = false;
			if (pendingUiRefreshTicks > 0)
			{
				--pendingUiRefreshTicks;
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!EmoteOrganizerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != net.runelite.api.MenuAction.CC_OP)
		{
			return;
		}

		Widget widget = event.getWidget();
		if (widget == null || !isEmoteWidget(widget))
		{
			return;
		}

		EmoteEntry entry = findEntryForWidget(widget);
		if (entry == null)
		{
			return;
		}
		String emoteKey = entry.getKey();

		String option = event.getMenuOption();
		if ("Favorite".equals(option) || "Unfavorite".equals(option))
		{
			Set<String> favorites = getFavorites();
			if ("Favorite".equals(option))
			{
				favorites.add(emoteKey);
			}
			else
			{
				favorites.remove(emoteKey);
			}

			setFavorites(favorites);
			scheduleUiRefresh(UI_REFRESH_TICKS);
			event.consume();
		}
	}

	private void scheduleUiRefresh(int ticks)
	{
		uiDirty = true;
		pendingUiRefreshTicks = Math.max(pendingUiRefreshTicks, ticks);
	}

	private void applyUiIfOpen()
	{
		if (isEmoteTabOpen())
		{
			scheduleUiRefresh(UI_REFRESH_TICKS);
			applyUi();
		}
	}

	private boolean applyUi()
	{
		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		Widget scrollable = client.getWidget(InterfaceID.Emote.SCROLLABLE);
		Widget overlay = client.getWidget(InterfaceID.Emote.OVERLAY);
		if (contents == null || scrollable == null)
		{
			return false;
		}

		if (overlay != null)
		{
			clearOverlay(overlay);
		}

		rebuildEmoteLayout(contents, scrollable);
		return true;
	}

	private void clearOverlay(Widget overlay)
	{
		overlay.deleteAllChildren();
	}

	private void rebuildEmoteLayout(Widget emoteContainer, Widget scrollable)
	{
		Widget[] children = emoteContainer.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		List<EmoteEntry> emotes = collectEmoteEntries(children);
		if (emotes.isEmpty())
		{
			return;
		}

		int emoteWidth = getWidgetWidth(emotes.get(0).getClickbox());
		int emoteHeight = getWidgetHeight(emotes.get(0).getClickbox());
		int containerWidth = scrollable.getWidth() > 0 ? scrollable.getWidth() : scrollable.getOriginalWidth();
		if (containerWidth <= 0)
		{
			containerWidth = DEFAULT_CONTAINER_WIDTH;
		}
		int columns = Math.max(1, containerWidth / Math.max(1, emoteWidth));

		Set<String> favorites = getFavorites();
		List<EmoteEntry> orderedEmotes = getOrderedEmotes(emotes);
		List<EmoteEntry> visibleEmotes = new ArrayList<>();

		for (EmoteEntry emote : orderedEmotes)
		{
			boolean favorite = favorites.contains(emote.getKey());
			boolean visible = passesFilter(favorite);

			if (visible)
			{
				visibleEmotes.add(emote);
			}
			else
			{
				resetEntryWidgetState(emote, true);
			}
		}

		for (int i = 0; i < visibleEmotes.size(); i++)
		{
			EmoteEntry emote = visibleEmotes.get(i);
			int x = (i % columns) * emoteWidth;
			int y = (i / columns) * emoteHeight;

			applyEntryPosition(emote, x, y);
			applyEntryHiddenState(emote, false);
			applyEntryOpacity(emote, 0);
			prepareGraphic(emote.getGraphic());
			applyWidgetActions(emote.getClickbox(), emote.getKey(), favorites);
			revalidateEntry(emote);
		}

		int totalRows = Math.max(1, (visibleEmotes.size() + columns - 1) / columns);
		scrollable.setScrollHeight(totalRows * emoteHeight);
		scrollable.revalidateScroll();
	}

	private List<EmoteEntry> collectEmoteEntries(Widget[] children)
	{
		List<EmoteEntryBuilder> builders = new ArrayList<>();
		for (Widget child : children)
		{
			if (child == null)
			{
				continue;
			}

			EmoteEntryBuilder builder = findOrCreateBuilder(builders, child.getOriginalX(), child.getOriginalY());
			if (child.getType() == WidgetType.GRAPHIC && child.getSpriteId() >= 0)
			{
				builder.graphic = child;
			}
			else if (child.getType() == WidgetType.RECTANGLE)
			{
				builder.clickbox = child;
				String label = getWidgetLabel(child);
				if (label.isEmpty())
				{
					label = deriveLabelFromActions(child);
				}
				if (!label.isEmpty())
				{
					builder.label = stripTags(label);
				}
			}
		}

		List<EmoteEntry> emotes = new ArrayList<>();
		for (EmoteEntryBuilder builder : builders)
		{
			Widget clickbox = builder.clickbox != null ? builder.clickbox : builder.graphic;
			Widget graphic = builder.graphic != null ? builder.graphic : builder.clickbox;
			if (clickbox == null || graphic == null)
			{
				continue;
			}

			if (builder.label.isEmpty())
			{
				continue;
			}

			emotes.add(new EmoteEntry(
				clickbox,
				graphic,
				builder.label,
				builder.label));
		}
		return emotes;
	}

	private EmoteEntryBuilder findOrCreateBuilder(List<EmoteEntryBuilder> builders, int originalX, int originalY)
	{
		for (EmoteEntryBuilder builder : builders)
		{
			if (builder.originalX == originalX && builder.originalY == originalY)
			{
				return builder;
			}
		}

		EmoteEntryBuilder builder = new EmoteEntryBuilder(originalX, originalY);
		builders.add(builder);
		return builder;
	}

	private List<EmoteEntry> getOrderedEmotes(List<EmoteEntry> emotes)
	{
		List<EmoteEntry> ordered = new ArrayList<>(emotes);
		Set<String> favorites = getFavorites();

		switch (config.sortMode())
		{
			case ALPHABETICAL:
				ordered.sort(Comparator.comparing(emote -> sortableName(emote.getDisplayName())));
				break;
			case FAVORITES_FIRST:
				ordered.sort(Comparator.comparingInt(emote -> favorites.contains(emote.getKey()) ? 0 : 1));
				break;
			default:
				break;
		}

		return ordered;
	}

	private boolean passesFilter(boolean favorite)
	{
		return !config.showFavoritesOnly() || favorite;
	}

	private void applyEntryPosition(EmoteEntry entry, int x, int y)
	{
		entry.getClickbox().setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
		entry.getGraphic().setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
	}

	private void applyEntryHiddenState(EmoteEntry entry, boolean hidden)
	{
		entry.getClickbox().setHidden(hidden);
		entry.getGraphic().setHidden(hidden);
	}

	private void applyEntryOpacity(EmoteEntry entry, int opacity)
	{
		applyOpacity(entry.getClickbox(), opacity);
		applyOpacity(entry.getGraphic(), opacity);
	}

	private void revalidateEntry(EmoteEntry entry)
	{
		entry.getClickbox().revalidate();
		entry.getGraphic().revalidate();
	}

	private void prepareGraphic(Widget graphic)
	{
		int widgetConfig = graphic.getClickMask();
		widgetConfig &= ~(transmitAction(0) | transmitAction(1) | transmitAction(FAVORITE_OP));
		graphic.setClickMask(widgetConfig);
		graphic.setNoClickThrough(false);
		graphic.setHasListener(false);
		clearGraphicActions(graphic);
	}

	private void resetEntryWidgetState(EmoteEntry entry, boolean hidden)
	{
		clearWidgetActions(entry.getClickbox());
		clearGraphicActions(entry.getGraphic());
		clearWidgetVisualState(entry.getClickbox(), hidden);
		clearWidgetVisualState(entry.getGraphic(), hidden);
		revalidateEntry(entry);
	}

	private void applyWidgetActions(Widget widget, String emoteKey, Set<String> favorites)
	{
		int widgetConfig = widget.getClickMask();
		widgetConfig |= transmitAction(FAVORITE_OP);
		widget.setClickMask(widgetConfig);
		widget.setAction(FAVORITE_OP, favorites.contains(emoteKey) ? "Unfavorite" : "Favorite");
	}

	private void clearWidgetActions(Widget widget)
	{
		widget.setAction(FAVORITE_OP, null);
	}

	private void clearGraphicActions(Widget widget)
	{
		widget.setAction(0, null);
		widget.setAction(1, null);
		widget.setAction(FAVORITE_OP, null);
		widget.setName("");
	}

	private void clearWidgetVisualState(Widget widget, boolean hidden)
	{
		widget.setHidden(hidden);
		applyOpacity(widget, 0);
	}

	private void applyOpacity(Widget widget, int opacity)
	{
		widget.setOpacity(opacity);

		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child != null)
				{
					applyOpacity(child, opacity);
				}
			}
		}

		Widget[] dynamicChildren = widget.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child != null)
				{
					applyOpacity(child, opacity);
				}
			}
		}

		Widget[] nestedChildren = widget.getNestedChildren();
		if (nestedChildren != null)
		{
			for (Widget child : nestedChildren)
			{
				if (child != null)
				{
					applyOpacity(child, opacity);
				}
			}
		}
	}

	private void redrawEmotes()
	{
		Widget widget = client.getWidget(InterfaceID.Emote.UNIVERSE);
		if (widget == null)
		{
			return;
		}

		runWidgetListener(widget, widget.getOnVarTransmitListener());
		runWidgetListener(widget, widget.getOnLoadListener());
	}

	private boolean runWidgetListener(Widget widget, Object[] listener)
	{
		if (listener == null)
		{
			return false;
		}

		ScriptEvent event = client.createScriptEvent(listener)
			.setSource(widget);
		event.run();
		return true;
	}

	private void teardownUi()
	{
		Widget overlay = client.getWidget(InterfaceID.Emote.OVERLAY);
		if (overlay != null)
		{
			overlay.deleteAllChildren();
		}

		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		if (contents == null)
		{
			return;
		}

		Widget[] children = contents.getDynamicChildren();
		if (children == null)
		{
			return;
		}

		for (EmoteEntry entry : collectEmoteEntries(children))
		{
			resetEntryWidgetState(entry, false);
		}
	}

	private boolean isEmoteTabOpen()
	{
		return client.getWidget(InterfaceID.Emote.CONTENTS) != null;
	}

	private boolean isEmoteWidget(Widget widget)
	{
		return WidgetUtil.componentToInterface(widget.getId()) == InterfaceID.EMOTE;
	}

	private int getWidgetWidth(Widget widget)
	{
		int width = widget.getWidth() > 0 ? widget.getWidth() : widget.getOriginalWidth();
		return width > 0 ? width : DEFAULT_EMOTE_WIDTH;
	}

	private int getWidgetHeight(Widget widget)
	{
		int height = widget.getHeight() > 0 ? widget.getHeight() : widget.getOriginalHeight();
		return height > 0 ? height : DEFAULT_EMOTE_HEIGHT;
	}

	private String getWidgetLabel(Widget widget)
	{
		String name = widget.getName();
		if (name != null && !name.isEmpty())
		{
			return name;
		}

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			return text;
		}

		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child != null)
				{
					String label = getWidgetLabel(child);
					if (!label.isEmpty())
					{
						return label;
					}
				}
			}
		}

		Widget[] dynamicChildren = widget.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child != null)
				{
					String label = getWidgetLabel(child);
					if (!label.isEmpty())
					{
						return label;
					}
				}
			}
		}

		return "";
	}

	private EmoteEntry findEntryForWidget(Widget widget)
	{
		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		if (contents == null)
		{
			return null;
		}

		Widget[] children = contents.getDynamicChildren();
		if (children == null)
		{
			return null;
		}

		for (EmoteEntry entry : collectEmoteEntries(children))
		{
			if (entry.getClickbox() == widget || entry.getGraphic() == widget)
			{
				return entry;
			}
		}

		return null;
	}

	private String deriveLabelFromActions(Widget widget)
	{
		String[] actions = widget.getActions();
		if (actions == null)
		{
			return "";
		}

		for (String action : actions)
		{
			if (action == null || action.isEmpty())
			{
				continue;
			}

			String stripped = stripTags(action);
			if (stripped.startsWith("Perform "))
			{
				return stripped.substring("Perform ".length()).trim();
			}
			if (stripped.startsWith("Loop "))
			{
				return stripped.substring("Loop ".length()).trim();
			}
		}

		return "";
	}

	private Set<String> getFavorites()
	{
		return getStringSet(CONFIG_FAVORITES);
	}

	private void setFavorites(Set<String> favorites)
	{
		setStringSet(CONFIG_FAVORITES, favorites);
	}

	private Set<String> getStringSet(String key)
	{
		String value = configManager.getConfiguration(EmoteOrganizerConfig.GROUP, key);
		if (value == null || value.isEmpty())
		{
			return new LinkedHashSet<>();
		}

		return new LinkedHashSet<>(Arrays.asList(value.split("\\|")));
	}

	private void setStringSet(String key, Set<String> values)
	{
		if (values.isEmpty())
		{
			configManager.unsetConfiguration(EmoteOrganizerConfig.GROUP, key);
		}
		else
		{
			configManager.setConfiguration(EmoteOrganizerConfig.GROUP, key, String.join("|", values));
		}
	}

	private static String stripTags(String input)
	{
		if (input == null || input.isEmpty())
		{
			return "";
		}

		return input.replaceAll("<[^>]+>", "").trim();
	}

	private static String sortableName(String input)
	{
		return input == null ? "" : input.toLowerCase();
	}

	@Value
	@AllArgsConstructor
	private static class EmoteEntry
	{
		Widget clickbox;
		Widget graphic;
		String key;
		String displayName;
	}

	private static class EmoteEntryBuilder
	{
		private final int originalX;
		private final int originalY;
		private Widget clickbox;
		private Widget graphic;
		private String label = "";

		private EmoteEntryBuilder(int originalX, int originalY)
		{
			this.originalX = originalX;
			this.originalY = originalY;
		}
	}
}
