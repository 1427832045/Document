package com.seer.srd.omron.fins.core;

import com.seer.srd.omron.fins.core.commands.FinsMemoryAreaWriteCommand;
import com.seer.srd.omron.fins.core.commands.FinsMemoryAreaWriteResponse;

@FunctionalInterface
public interface MemoryAreaWriteCommandHandler {

	FinsMemoryAreaWriteResponse handle(FinsMemoryAreaWriteCommand command);
	
}
