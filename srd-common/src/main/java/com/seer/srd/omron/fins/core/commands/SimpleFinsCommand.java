package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;

public abstract class SimpleFinsCommand implements FinsCommand {

	private final FinsCommandCode commandCode;

	protected SimpleFinsCommand(FinsCommandCode commandCode) {
		this.commandCode = commandCode;
	}

	@Override
	public FinsCommandCode getCommandCode() {
		return this.commandCode;
	}

}
