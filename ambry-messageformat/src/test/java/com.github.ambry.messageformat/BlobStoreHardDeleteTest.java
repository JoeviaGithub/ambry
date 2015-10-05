package com.github.ambry.messageformat;

import com.github.ambry.store.HardDeleteInfo;
import com.github.ambry.store.MessageInfo;
import com.github.ambry.store.MessageReadSet;
import com.github.ambry.store.MessageStoreHardDelete;
import com.github.ambry.store.Read;
import com.github.ambry.store.StoreKey;
import com.github.ambry.store.StoreKeyFactory;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;


public class BlobStoreHardDeleteTest {

  public class ReadImp implements Read {

    MockReadSet readSet = new MockReadSet();
    List<byte[]> recoveryInfoList = new ArrayList<byte[]>();
    ByteBuffer buffer;
    public StoreKey[] keys =
        {new MockId("id1"), new MockId("id2"), new MockId("id3"), new MockId("id4"), new MockId("id5")};
    long expectedExpirationTimeMs = 0;

    public ArrayList<Long> initialize()
        throws MessageFormatException, IOException {
      // write 3 new blob messages, and delete update messages. write the last
      // message that is partial
      final int USERMETADATA_SIZE = 2000;
      final int BLOB_SIZE = 4000;
      byte[] usermetadata = new byte[USERMETADATA_SIZE];
      byte[] blob = new byte[BLOB_SIZE];
      new Random().nextBytes(usermetadata);
      new Random().nextBytes(blob);

      ArrayList<Long> msgOffsets = new ArrayList<Long>();

      BlobProperties blobProperties = new BlobProperties(BLOB_SIZE, "test", "mem1", "img", false, 9999);
      expectedExpirationTimeMs =
          Utils.addSecondsToEpochTime(blobProperties.getCreationTimeInMs(), blobProperties.getTimeToLiveInSeconds());
      PutMessageFormatInputStream msg0 =
          new PutMessageFormatInputStream(keys[0], blobProperties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(blob)), BLOB_SIZE);

      PutMessageFormatInputStream msg1 =
          new PutMessageFormatInputStream(keys[1], new BlobProperties(BLOB_SIZE, "test"), ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(blob)), BLOB_SIZE);

      PutMessageFormatInputStream msg2 =
          new PutMessageFormatInputStream(keys[2], new BlobProperties(BLOB_SIZE, "test"), ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(blob)), BLOB_SIZE);

      DeleteMessageFormatInputStream msg3d = new DeleteMessageFormatInputStream(keys[1]);

      PutMessageFormatInputStream msg4 =
          new PutMessageFormatInputStream(keys[3], new BlobProperties(BLOB_SIZE, "test"), ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(blob)), BLOB_SIZE);

      PutMessageFormatInputStream msg5 =
          new PutMessageFormatInputStream(keys[4], new BlobProperties(BLOB_SIZE, "test"), ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(blob)), BLOB_SIZE);

      buffer = ByteBuffer.allocate((int) (msg0.getSize() +
          msg1.getSize() +
          msg2.getSize() +
          msg3d.getSize() +
          msg4.getSize() +
          msg5.getSize()));

      Long offset = 0L;
      msgOffsets.add(offset);
      offset += msg0.getSize();
      msgOffsets.add(offset);
      offset += msg1.getSize();
      msgOffsets.add(offset);
      offset += msg2.getSize();
      msgOffsets.add(offset);
      offset += msg3d.getSize();
      msgOffsets.add(offset);
      offset += msg4.getSize();
      msgOffsets.add(offset);
      offset += msg5.getSize();
      msgOffsets.add(offset);

      // msg0: A good message that will not be part of hard deletes.
      writeToBuffer(msg0, (int) msg0.getSize());

      // msg1: A good message that will be part of hard deletes, but not part of recovery.
      readSet.addMessage(buffer.position(), keys[1], (int) msg1.getSize());
      writeToBuffer(msg1, (int) msg1.getSize());

      // msg2: A good message that will be part of hard delete, with recoveryInfo.
      readSet.addMessage(buffer.position(), keys[2], (int) msg2.getSize());
      writeToBuffer(msg2, (int) msg2.getSize());
      HardDeleteRecoveryMetadata hardDeleteRecoveryMetadata =
          new HardDeleteRecoveryMetadata(MessageFormatRecord.Message_Header_Version_V1,
              MessageFormatRecord.UserMetadata_Version_V1, USERMETADATA_SIZE, MessageFormatRecord.Blob_Version_V1,
              BLOB_SIZE, keys[2]);
      recoveryInfoList.add(hardDeleteRecoveryMetadata.toBytes());

      // msg3d: Delete Record. Not part of readSet.
      writeToBuffer(msg3d, (int) msg3d.getSize());

      // msg4: A message with blob record corrupted that will be part of hard delete, with recoveryInfo.
      // This should succeed.
      readSet.addMessage(buffer.position(), keys[3], (int) msg4.getSize());
      writeToBufferAndCorruptBlobRecord(msg4, (int) msg4.getSize());
      hardDeleteRecoveryMetadata = new HardDeleteRecoveryMetadata(MessageFormatRecord.Message_Header_Version_V1,
          MessageFormatRecord.UserMetadata_Version_V1, USERMETADATA_SIZE, MessageFormatRecord.Blob_Version_V1,
          BLOB_SIZE, keys[3]);
      recoveryInfoList.add(hardDeleteRecoveryMetadata.toBytes());

      // msg5: A message with blob record corrupted that will be part of hard delete, without recoveryInfo.
      // This should fail.
      readSet.addMessage(buffer.position(), keys[4], (int) msg5.getSize());
      writeToBufferAndCorruptBlobRecord(msg5, (int) msg5.getSize());
      buffer.position(0);
      return msgOffsets;
    }

    private void writeToBuffer(MessageFormatInputStream stream, int sizeToWrite)
        throws IOException {
      long sizeWritten = 0;
      while (sizeWritten < sizeToWrite) {
        int read = stream.read(buffer.array(), buffer.position(), (int) sizeToWrite);
        sizeWritten += read;
        buffer.position(buffer.position() + (int) sizeWritten);
      }
    }

    private void writeToBufferAndCorruptBlobRecord(MessageFormatInputStream stream, int sizeToWrite)
        throws IOException {
      long sizeWritten = 0;
      while (sizeWritten < sizeToWrite) {
        int read = stream.read(buffer.array(), buffer.position(), (int) sizeToWrite);
        sizeWritten += read;
        buffer.position(buffer.position() + (int) sizeWritten);

        //corrupt the last byte of the blob record, just before the crc, so the crc fails.
        int indexToCorrupt = buffer.position() - MessageFormatRecord.Crc_Size - 1;
        byte b = buffer.get(indexToCorrupt);
        b = (byte) (int) ~b;
        buffer.put(indexToCorrupt, b);
      }
    }

    @Override
    public void readInto(ByteBuffer bufferToWrite, long position)
        throws IOException {
      bufferToWrite.put(buffer.array(), (int) position, bufferToWrite.remaining());
    }

    public int getSize() {
      return buffer.capacity();
    }

    public MessageReadSet getMessageReadSet() {
      return readSet;
    }

    public List<byte[]> getRecoveryInfoList() {
      return recoveryInfoList;
    }

    class Message {
      int position;
      StoreKey key;
      int size;

      Message(int position, StoreKey key, int size) {
        this.position = position;
        this.key = key;
        this.size = size;
      }
    }

    class MockReadSet implements MessageReadSet {
      List<Message> messageList = new ArrayList<Message>();

      void addMessage(int position, StoreKey key, int size) {
        messageList.add(new Message(position, key, size));
      }

      @Override
      public long writeTo(int index, WritableByteChannel channel, long relativeOffset, long maxSize)
          throws IOException {
        buffer.position(messageList.get(index).position + (int) relativeOffset);
        byte[] toReturn = new byte[Math.min(messageList.get(index).size, (int) maxSize)];
        buffer.get(toReturn);
        return channel.write(ByteBuffer.wrap(toReturn));
      }

      @Override
      public int count() {
        return messageList.size();
      }

      @Override
      public long sizeInBytes(int index) {
        return messageList.get(index).size;
      }

      @Override
      public StoreKey getKeyAt(int index) {
        return messageList.get(index).key;
      }
    }
  }

  @Test
  public void blobStoreHardDeleteTest()
      throws MessageFormatException, IOException {
    MessageStoreHardDelete hardDelete = new BlobStoreHardDelete();

    StoreKeyFactory keyFactory = new MockIdFactory();
    // create log and write to it
    ReadImp readImp = new ReadImp();
    ArrayList<Long> msgOffsets = readImp.initialize();

    // read a put record.
    MessageInfo info = hardDelete.getMessageInfo(readImp, msgOffsets.get(0), keyFactory);

    // read a delete record.
    hardDelete.getMessageInfo(readImp, msgOffsets.get(3), keyFactory);

    // read from a random location.
    try {
      hardDelete.getMessageInfo(readImp, (msgOffsets.get(0) + msgOffsets.get(1))/2, keyFactory);
      Assert.assertTrue(false);
    } catch (IOException e) {
    }

    // offset outside of valid range.
    try {
      hardDelete.getMessageInfo(readImp, (msgOffsets.get(msgOffsets.size() - 1) + 1), keyFactory);
      Assert.assertTrue(false);
    } catch (IOException e) {
    }

    Iterator<HardDeleteInfo> iter =
        hardDelete.getHardDeleteMessages(readImp.getMessageReadSet(), keyFactory, readImp.getRecoveryInfoList());

    List<HardDeleteInfo> hardDeletedList = new ArrayList<HardDeleteInfo>();

    while (iter.hasNext()) {
      hardDeletedList.add(iter.next());
    }

    // msg1
    Assert.assertNotNull(hardDeletedList.get(0));
    // msg2
    Assert.assertNotNull(hardDeletedList.get(1));
    // msg4
    Assert.assertNotNull(hardDeletedList.get(2));
    // msg5 - NULL.
    Assert.assertNull(hardDeletedList.get(3));
  }
}

