package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;
import com.seer.srd.omron.fins.core.FinsEndCode;

public abstract class SimpleFinsResponse implements FinsResponse {

	private final FinsCommandCode commandCode;
	private final FinsEndCode endCode;

	protected SimpleFinsResponse(final FinsCommandCode commandCode, final FinsEndCode endCode) {
		this.commandCode = commandCode;
		this.endCode = endCode;
	}

	@Override
	public FinsCommandCode getCommandCode() {
		return this.commandCode;
	}

	@Override
	public FinsEndCode getEndCode() {
		return this.endCode;
	}

}
