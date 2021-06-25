package com.seer.srd.omron.fins.core;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.seer.srd.omron.fins.core.commands.FinsCommand;
import com.seer.srd.omron.fins.core.commands.FinsMemoryAreaWriteWordCommand;
import com.seer.srd.omron.fins.core.commands.FinsUnsupportedCommandException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FinsCommandBuilder {

	private final static Logger logger = LoggerFactory.getLogger(FinsCommandBuilder.class);
	
	private static final  FinsCommandBuilder INSTANCE = new FinsCommandBuilder();
	
	private static Map<FinsCommandCode, FinsCommandBuilder> commandBuilders = new HashMap<>();
	
	private FinsCommandBuilder() {}
	
    public static FinsCommandBuilder getInstance() {
       return FinsCommandBuilder.INSTANCE;
    }
	
	public static void registerCommandBuilder(FinsCommandCode finsCommandCode, FinsCommandBuilder commandBuilder) {
		logger.debug(String.format("Registering command handler for command code 0x%04x", finsCommandCode));
		FinsCommandBuilder.commandBuilders.put(finsCommandCode, commandBuilder);
	}

	public FinsCommand parseFrom(byte[] commandPayload) throws FinsUnsupportedCommandException {
		// Peek at the command code bytes and delegate the building to the appropriate FinsCommandBuilder
		ByteBuffer buf = ByteBuffer.wrap(commandPayload);
		short commandCodeValue = buf.getShort();
		FinsCommandCode commandCode = FinsCommandCode.valueOf(commandCodeValue).get();

		if (commandCode == FinsCommandCode.MEMORY_AREA_WRITE) {
			FinsIoMemoryArea memoryAreaCode = FinsIoMemoryArea.valueOf(buf.get()).get();
			switch (memoryAreaCode.getDataByteSize()) {
				case 1:
					// Bit data
					// TODO
					break;

				case 2:
					// Word data
					return FinsMemoryAreaWriteWordCommand.Builder.parseFrom(commandPayload);

				case 4:
					// Double word data
					// TODO
					break;
			}
		}

		// We dont support this command code
		throw new FinsUnsupportedCommandException();
	}
	
}
