package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;

public interface FinsCommand {

	FinsCommandCode getCommandCode();
	
	byte[] getBytes();
	
}
