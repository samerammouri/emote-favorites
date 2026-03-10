package com.emoteorganizer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class EmoteOrganizerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(EmoteOrganizerPlugin.class);
		RuneLite.main(args);
	}
}
