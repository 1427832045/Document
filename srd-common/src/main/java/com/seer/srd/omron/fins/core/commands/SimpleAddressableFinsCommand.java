package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;
import com.seer.srd.omron.fins.core.FinsIoAddress;

public abstract class SimpleAddressableFinsCommand extends SimpleFinsCommand {

	private final FinsIoAddress ioAddress;

	public SimpleAddressableFinsCommand(final FinsCommandCode commandCode, final FinsIoAddress ioAddress) {
		super(commandCode);
		this.ioAddress = ioAddress;
	}

	public FinsIoAddress getIoAddress() {
		return this.ioAddress;
	}

}
