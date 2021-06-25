package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;
import com.seer.srd.omron.fins.core.FinsEndCode;

public interface FinsResponse {

	FinsCommandCode getCommandCode();
	
	FinsEndCode getEndCode();
	
	byte[] getBytes();
	
}
