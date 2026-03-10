package com.emoteorganizer;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum EmoteSortMode
{
	ALPHABETICAL("Alphabetical"),
	FAVORITES_FIRST("Favorites First"),
	;

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
