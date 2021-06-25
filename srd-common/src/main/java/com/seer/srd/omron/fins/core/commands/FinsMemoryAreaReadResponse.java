package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;
import com.seer.srd.omron.fins.core.FinsEndCode;

import java.util.Collections;
import java.util.List;

public abstract class FinsMemoryAreaReadResponse<T> extends SimpleFinsResponse {

	private final List<T> items;

	public FinsMemoryAreaReadResponse(final FinsEndCode errorCode, final List<T> items) {
		super(FinsCommandCode.MEMORY_AREA_READ, errorCode);
		this.items = items;
	}

	public List<T> getItems() {
		return Collections.unmodifiableList(this.items);
	}
	
}
