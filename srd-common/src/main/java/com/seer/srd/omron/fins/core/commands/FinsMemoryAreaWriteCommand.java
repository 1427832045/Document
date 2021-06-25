package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;
import com.seer.srd.omron.fins.core.FinsIoAddress;

public abstract class FinsMemoryAreaWriteCommand extends SimpleAddressableFinsCommand {

	public FinsMemoryAreaWriteCommand(FinsCommandCode commandCode, FinsIoAddress ioAddress) {
		super(commandCode, ioAddress);
	}
	
}
