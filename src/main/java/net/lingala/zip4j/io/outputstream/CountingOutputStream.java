package net.lingala.zip4j.io.outputstream;

import net.lingala.zip4j.exception.ZipException;

import java.io.IOException;
import java.io.OutputStream;

public class CountingOutputStream extends OutputStream {

  private OutputStream outputStream;
  private long numberOfBytesWritten;

  public CountingOutputStream(OutputStream outputStream) {
    this.outputStream = outputStream;
  }

  @Override
  public void write(int b) throws IOException {
    write((byte) b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    outputStream.write(b, off, len);
    numberOfBytesWritten += len;
  }

  public int getCurrentSplitFileCounter() {
    if (isSplitOutputStream()) {
      return ((SplitOutputStream) outputStream).getCurrSplitFileCounter();
    }

    return 0;
  }

  public long getOffsetForNextEntry() throws IOException {
    if (isSplitOutputStream()) {
      return ((SplitOutputStream) outputStream).getFilePointer();
    }

    return numberOfBytesWritten;
  }

  public long getSplitLength() {
    if (isSplitOutputStream()) {
      return ((SplitOutputStream) outputStream).getSplitLength();
    }

    return 0;
  }

  public boolean isSplitOutputStream() {
    return outputStream instanceof SplitOutputStream
        && ((SplitOutputStream)outputStream).isSplitZipFile();
  }

  public long getNumberOfBytesWritten() throws IOException {
    if (isSplitOutputStream()) {
      return ((SplitOutputStream) outputStream).getFilePointer();
    }

    return numberOfBytesWritten;
  }

  public boolean checkBuffSizeAndStartNextSplitFile(int bufferSize) throws ZipException {
    if (!isSplitOutputStream()) {
      return false;
    }

    return ((SplitOutputStream)outputStream).checkBufferSizeAndStartNextSplitFile(bufferSize);
  }

  public long getFilePointer() throws IOException {
    if (isSplitOutputStream()) {
      return ((SplitOutputStream) outputStream).getFilePointer();
    }

    return numberOfBytesWritten;
  }
}
