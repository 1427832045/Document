package com.seer.srd.omron.fins.codec;

import java.net.InetSocketAddress;
import java.util.List;

import com.seer.srd.omron.fins.core.FinsFrame;
import com.seer.srd.omron.fins.core.FinsFrameBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.ReferenceCountUtil;

public class FinsFrameUdpCodec extends MessageToMessageCodec<DatagramPacket, FinsFrame> {

	private InetSocketAddress destinationAddress = null;

	public FinsFrameUdpCodec() {}

	public FinsFrameUdpCodec(InetSocketAddress destinationAddress) {
		this.destinationAddress = destinationAddress;
	}


	@Override
	protected void encode(ChannelHandlerContext ctx, FinsFrame frame, List<Object> out) throws Exception {
		try {
			ByteBuf buf = Unpooled.wrappedBuffer(frame.toByteArray());
			DatagramPacket packet = new DatagramPacket(buf, this.destinationAddress);
			out.add(packet);
		} finally {
			ReferenceCountUtil.release(frame);
		}
	}

	@Override
	protected void decode(ChannelHandlerContext context, DatagramPacket packet, List<Object> out) throws Exception {
		byte[] data = new byte[packet.content()
			.readableBytes()];
		packet.content()
			.readBytes(data);
		FinsFrame frame = FinsFrameBuilder.parseFrom(data);
		out.add(frame);
	}
	
}
