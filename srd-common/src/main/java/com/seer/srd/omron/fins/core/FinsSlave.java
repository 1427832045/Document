package com.seer.srd.omron.fins.core;

import java.util.Optional;

public interface FinsSlave extends AutoCloseable {

	void start() throws FinsSlaveException;
	
	void shutdown();
	
	void setMemoryAreaWriteHandler(MemoryAreaWriteCommandHandler handler);
	
	Optional<MemoryAreaWriteCommandHandler> getMemoryAreaWriteHandler();
	
}
