package com.seer.srd.omron.fins.core.commands;

import com.seer.srd.omron.fins.core.FinsCommandCode;
import com.seer.srd.omron.fins.core.FinsEndCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class FinsMemoryAreaReadWordResponse extends FinsMemoryAreaReadResponse<Short> {

	private static final Logger logger = LoggerFactory.getLogger(FinsMemoryAreaReadWordResponse.class);

	public FinsMemoryAreaReadWordResponse(final FinsEndCode errorCode, final List<Short> items) {
		super(errorCode, items);
	}
	
	@Override
	public byte[] getBytes() {
		ByteBuffer buf = ByteBuffer.allocate(512);
		buf.putShort(this.getCommandCode().getValue());
		buf.putShort(this.getEndCode().getValue());

		List<Short> items = this.getItems();
		buf.putShort((short) items.size());
		items.forEach(buf::putShort);
		
		buf.flip();
		byte[] bytes = new byte[buf.remaining()];
		buf.get(bytes);

		return bytes;
	}

	public static FinsMemoryAreaReadWordResponse parseFrom(final byte[] data, final short itemCount) {
		ByteBuffer buf = ByteBuffer.wrap(data);
		FinsCommandCode commandCode = FinsCommandCode.valueOf(buf.getShort()).get();
		short endCodeRaw = buf.getShort();
		
		if ((endCodeRaw & (1L << 15)) != 0) {
			logger.debug("A network relay error, probably need to read more bytes");
		   short relayError = buf.getShort();
			logger.debug(String.format("Relay error 0x%04x", relayError));
		}

		if ((endCodeRaw & (1L << 7)) != 0) {
			logger.debug("A fatal CPU error");
		}

		if ((endCodeRaw & (1L << 6)) != 0) {
			logger.debug("A minor CPU error");
		}

//		logger.debug(String.format("EndCode 0x%04x", endCodeRaw));
		FinsEndCode endCode = FinsEndCode.valueOf(endCodeRaw).get();

		if (commandCode != FinsCommandCode.MEMORY_AREA_READ) {
			logger.debug("invalid command code {} for the response type", commandCode);
			//
		}
		
		List<Short> items = new ArrayList<>();
		
		if (endCode == FinsEndCode.NORMAL_COMPLETION) {
			for (int i = 0; i < itemCount; i++) {
				items.add(buf.getShort());
			}
		}
		
		return new FinsMemoryAreaReadWordResponse(endCode, items);
	}
	
	public static FinsMemoryAreaReadWordResponse parseFrom(final byte[] data, final int itemCount) {
		return parseFrom(data, (short) itemCount);
	}
	
}
