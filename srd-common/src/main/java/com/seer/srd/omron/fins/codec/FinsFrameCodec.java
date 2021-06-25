package com.seer.srd.omron.fins.codec;

import java.util.List;

import com.seer.srd.omron.fins.core.FinsFrame;
import com.seer.srd.omron.fins.core.FinsFrameBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

public class FinsFrameCodec extends ByteToMessageCodec<FinsFrame> {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Override
	protected void encode(ChannelHandlerContext context, FinsFrame finsFrame, ByteBuf out) throws Exception {
		out.writeBytes(finsFrame.toByteArray());
		logger.debug("Encoded FINS frame");
		logger.debug(finsFrame.toString());
	}
	
	@Override
	protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) throws Exception {

		byte[] data = new byte[in.readableBytes()];
		in.readBytes(data);
		FinsFrame finsFrame = FinsFrameBuilder.parseFrom(data);

		logger.debug("Decoded FINS frame");
		logger.debug(finsFrame.toString());

		out.add(finsFrame);
	}
}
