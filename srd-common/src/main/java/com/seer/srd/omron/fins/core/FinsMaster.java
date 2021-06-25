package com.seer.srd.omron.fins.core;

import java.util.List;

public interface FinsMaster extends AutoCloseable {

	void connect() throws FinsMasterException;

	void disconnect();

	short readWord(FinsNodeAddress destination, FinsIoAddress address) throws FinsMasterException;

	List<Short> readWords(FinsNodeAddress destination, FinsIoAddress address, short itemCount) throws FinsMasterException;

	List<Short> readWords(FinsNodeAddress destination, FinsIoAddress address, int itemCount) throws FinsMasterException;

	Bit readBit(FinsNodeAddress destination, FinsIoAddress address) throws FinsMasterException;

	List<Bit> readBits(FinsNodeAddress destination, FinsIoAddress address, short itemCount) throws FinsMasterException;

	List<Bit> readBits(FinsNodeAddress destination, FinsIoAddress address, int itemCount) throws FinsMasterException;

	List<Short> readMultipleWords(FinsNodeAddress destination, List<FinsIoAddress> addresses) throws FinsMasterException;

	void writeWord(FinsNodeAddress destination, FinsIoAddress address, short item) throws FinsMasterException;

	void writeWords(FinsNodeAddress destination, FinsIoAddress address, List<Short> items) throws FinsMasterException;

	void writeMultipleWords(FinsNodeAddress destination, List<FinsIoAddress> addresses, List<Short> items) throws FinsMasterException;

	String readString(FinsNodeAddress destination, FinsIoAddress address, int wordLength) throws FinsMasterException;

	String readString(FinsNodeAddress destination, FinsIoAddress address, short wordLength) throws FinsMasterException;

}
