package com.seer.srd.omron.fins.core.util;

import javax.xml.bind.DatatypeConverter;

public class StringUtilities {
	public static String getHexString(byte[] bytes) {
		return DatatypeConverter.printHexBinary(bytes).replaceAll(".{2}(?!$)", "$0 ");
	}
}
