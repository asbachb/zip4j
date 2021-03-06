/*
 * Copyright 2010 Srikanth Reddy Lingala
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lingala.zip4j.headers;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.AESExtraDataRecord;
import net.lingala.zip4j.model.CentralDirectory;
import net.lingala.zip4j.model.DigitalSignature;
import net.lingala.zip4j.model.EndOfCentralDirectoryRecord;
import net.lingala.zip4j.model.ExtraDataRecord;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.LocalFileHeader;
import net.lingala.zip4j.model.Zip64EndOfCentralDirectoryLocator;
import net.lingala.zip4j.model.Zip64EndOfCentralDirectoryRecord;
import net.lingala.zip4j.model.Zip64ExtendedInfo;
import net.lingala.zip4j.model.ZipModel;
import net.lingala.zip4j.util.Raw;
import net.lingala.zip4j.util.Zip4jUtil;
import net.lingala.zip4j.zip.AesKeyStrength;
import net.lingala.zip4j.zip.CompressionMethod;
import net.lingala.zip4j.zip.EncryptionMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import static net.lingala.zip4j.util.InternalZipConstants.ENDHDR;
import static net.lingala.zip4j.util.InternalZipConstants.UFT8_NAMES_FLAG;

/**
 * Helper class to read header information for the zip file
 */
public class HeaderReader {

  private ZipModel zipModel;

  public ZipModel readAllHeaders(RandomAccessFile zip4jRaf, String fileNameCharset) throws ZipException {
    zipModel = new ZipModel();
    zipModel.setFileNameCharset(fileNameCharset);
    zipModel.setEndOfCentralDirectoryRecord(readEndOfCentralDirectoryRecord(zip4jRaf));

    // If file is Zip64 format, Zip64 headers have to be read before reading central directory
    zipModel.setZip64EndOfCentralDirectoryLocator(readZip64EndCentralDirLocator(zip4jRaf));

    if (zipModel.isZip64Format()) {
      zipModel.setZip64EndOfCentralDirectoryRecord(readZip64EndCentralDirRec(zip4jRaf));
      if (zipModel.getZip64EndOfCentralDirectoryRecord() != null
          && zipModel.getZip64EndOfCentralDirectoryRecord().getNoOfThisDisk() > 0) {
        zipModel.setSplitArchive(true);
      } else {
        zipModel.setSplitArchive(false);
      }
    }

    zipModel.setCentralDirectory(readCentralDirectory(zip4jRaf));

    return zipModel;
  }

  private EndOfCentralDirectoryRecord readEndOfCentralDirectoryRecord(RandomAccessFile zip4jRaf) throws ZipException {
    try {
      byte[] ebs = new byte[4];
      long pos = zip4jRaf.length() - ENDHDR;

      EndOfCentralDirectoryRecord endOfCentralDirectoryRecord = new EndOfCentralDirectoryRecord();
      int counter = 0;
      do {
        zip4jRaf.seek(pos--);
        counter++;
      } while ((Raw.readLeInt(zip4jRaf, ebs) != HeaderSignature.END_OF_CENTRAL_DIRECTORY.getValue()) && counter <= 3000);

      if ((Raw.readIntLittleEndian(ebs, 0) != HeaderSignature.END_OF_CENTRAL_DIRECTORY.getValue())) {
        throw new ZipException("zip headers not found. probably not a zip file");
      }

      byte[] intBuff = new byte[4];
      byte[] shortBuff = new byte[2];

      //End of central record signature
      endOfCentralDirectoryRecord.setSignature(HeaderSignature.END_OF_CENTRAL_DIRECTORY);

      //number of this disk
      readIntoBuff(zip4jRaf, shortBuff);
      endOfCentralDirectoryRecord.setNumberOfThisDisk(Raw.readShortLittleEndian(shortBuff, 0));

      //number of the disk with the start of the central directory
      readIntoBuff(zip4jRaf, shortBuff);
      endOfCentralDirectoryRecord.setNumberOfThisDiskStartOfCentralDir(Raw.readShortLittleEndian(shortBuff, 0));

      //total number of entries in the central directory on this disk
      readIntoBuff(zip4jRaf, shortBuff);
      endOfCentralDirectoryRecord.setTotalNumberOfEntriesInCentralDirectoryOnThisDisk(Raw.readShortLittleEndian(shortBuff, 0));

      //total number of entries in the central directory
      readIntoBuff(zip4jRaf, shortBuff);
      endOfCentralDirectoryRecord.setTotalNumberOfEntriesInCentralDirectory(Raw.readShortLittleEndian(shortBuff, 0));

      //size of the central directory
      readIntoBuff(zip4jRaf, intBuff);
      endOfCentralDirectoryRecord.setSizeOfCentralDirectory(Raw.readIntLittleEndian(intBuff, 0));

      //offset of start of central directory with respect to the starting disk number
      readIntoBuff(zip4jRaf, intBuff);
      byte[] longBuff = getLongByteFromIntByte(intBuff);
      endOfCentralDirectoryRecord.setOffsetOfStartOfCentralDirectory(Raw.readLongLittleEndian(longBuff, 0));

      //.ZIP file comment length
      readIntoBuff(zip4jRaf, shortBuff);
      int commentLength = Raw.readShortLittleEndian(shortBuff, 0);
      endOfCentralDirectoryRecord.setCommentLength(commentLength);

      //.ZIP file comment
      if (commentLength > 0) {
        byte[] commentBuf = new byte[commentLength];
        readIntoBuff(zip4jRaf, commentBuf);
        endOfCentralDirectoryRecord.setComment(new String(commentBuf));
        endOfCentralDirectoryRecord.setCommentBytes(commentBuf);
      } else {
        endOfCentralDirectoryRecord.setComment(null);
      }

      int diskNumber = endOfCentralDirectoryRecord.getNumberOfThisDisk();
      if (diskNumber > 0) {
        zipModel.setSplitArchive(true);
      } else {
        zipModel.setSplitArchive(false);
      }

      return endOfCentralDirectoryRecord;
    } catch (IOException e) {
      throw new ZipException("Probably not a zip file or a corrupted zip file", e);
    }
  }

  /**
   * Reads central directory information for the zip file
   *
   * @return {@link CentralDirectory}
   * @throws ZipException
   */
  private CentralDirectory readCentralDirectory(RandomAccessFile zip4jRaf) throws ZipException {
    if (zipModel.getEndOfCentralDirectoryRecord() == null) {
      throw new ZipException("EndCentralRecord was null, maybe a corrupt zip file");
    }

    try {
      CentralDirectory centralDirectory = new CentralDirectory();
      ArrayList fileHeaderList = new ArrayList();

      EndOfCentralDirectoryRecord endOfCentralDirectoryRecord = zipModel.getEndOfCentralDirectoryRecord();
      long offSetStartCentralDir = endOfCentralDirectoryRecord.getOffsetOfStartOfCentralDirectory();
      int centralDirEntryCount = endOfCentralDirectoryRecord.getTotalNumberOfEntriesInCentralDirectory();

      if (zipModel.isZip64Format()) {
        offSetStartCentralDir = zipModel.getZip64EndOfCentralDirectoryRecord().getOffsetStartCenDirWRTStartDiskNo();
        centralDirEntryCount = (int) zipModel.getZip64EndOfCentralDirectoryRecord().getTotNoOfEntriesInCentralDir();
      }

      zip4jRaf.seek(offSetStartCentralDir);

      byte[] intBuff = new byte[4];
      byte[] shortBuff = new byte[2];
      byte[] longBuff = new byte[8];

      for (int i = 0; i < centralDirEntryCount; i++) {
        FileHeader fileHeader = new FileHeader();

        //FileHeader Signature
        readIntoBuff(zip4jRaf, intBuff);
        int signature = Raw.readIntLittleEndian(intBuff, 0);
        if (signature != HeaderSignature.CENTRAL_DIRECTORY.getValue()) {
          throw new ZipException("Expected central directory entry not found (#" + (i + 1) + ")");
        }
        fileHeader.setSignature(HeaderSignature.CENTRAL_DIRECTORY);

        //version made by
        readIntoBuff(zip4jRaf, shortBuff);
        fileHeader.setVersionMadeBy(Raw.readShortLittleEndian(shortBuff, 0));

        //version needed to extract
        readIntoBuff(zip4jRaf, shortBuff);
        fileHeader.setVersionNeededToExtract(Raw.readShortLittleEndian(shortBuff, 0));

        //general purpose bit flag
        readIntoBuff(zip4jRaf, shortBuff);
        fileHeader.setFileNameUTF8Encoded((Raw.readShortLittleEndian(shortBuff, 0) & UFT8_NAMES_FLAG) != 0);
        int firstByte = shortBuff[0];
        int result = firstByte & 1;
        if (result != 0) {
          fileHeader.setEncrypted(true);
        }
        fileHeader.setGeneralPurposeFlag((byte[]) shortBuff.clone());

        //Check if data descriptor exists for local file header
        fileHeader.setDataDescriptorExists(firstByte >> 3 == 1);

        //compression method
        readIntoBuff(zip4jRaf, shortBuff);
        int compressionTypeCode = Raw.readShortLittleEndian(shortBuff, 0);
        fileHeader.setCompressionMethod(CompressionMethod.getCompressionMethodFromCode(compressionTypeCode));

        //last mod file time
        readIntoBuff(zip4jRaf, intBuff);
        fileHeader.setLastModifiedTime(Raw.readIntLittleEndian(intBuff, 0));

        //crc-32
        readIntoBuff(zip4jRaf, intBuff);
        fileHeader.setCrc32(Raw.readIntLittleEndian(intBuff, 0));
        fileHeader.setCrcRawData((byte[]) intBuff.clone());

        //compressed size
        readIntoBuff(zip4jRaf, intBuff);
        longBuff = getLongByteFromIntByte(intBuff);
        fileHeader.setCompressedSize(Raw.readLongLittleEndian(longBuff, 0));

        //uncompressed size
        readIntoBuff(zip4jRaf, intBuff);
        longBuff = getLongByteFromIntByte(intBuff);
        fileHeader.setUncompressedSize(Raw.readLongLittleEndian(longBuff, 0));

        //file name length
        readIntoBuff(zip4jRaf, shortBuff);
        int fileNameLength = Raw.readShortLittleEndian(shortBuff, 0);
        fileHeader.setFileNameLength(fileNameLength);

        //extra field length
        readIntoBuff(zip4jRaf, shortBuff);
        int extraFieldLength = Raw.readShortLittleEndian(shortBuff, 0);
        fileHeader.setExtraFieldLength(extraFieldLength);

        //file comment length
        readIntoBuff(zip4jRaf, shortBuff);
        int fileCommentLength = Raw.readShortLittleEndian(shortBuff, 0);
        fileHeader.setFileComment(new String(shortBuff));

        //disk number start
        readIntoBuff(zip4jRaf, shortBuff);
        fileHeader.setDiskNumberStart(Raw.readShortLittleEndian(shortBuff, 0));

        //internal file attributes
        readIntoBuff(zip4jRaf, shortBuff);
        fileHeader.setInternalFileAttributes((byte[]) shortBuff.clone());

        //external file attributes
        readIntoBuff(zip4jRaf, intBuff);
        fileHeader.setExternalFileAttributes((byte[]) intBuff.clone());

        //relative offset of local header
        readIntoBuff(zip4jRaf, intBuff);
        //Commented on 26.08.2010. Revert back if any issues
        //fileHeader.setOffsetLocalHeader((Raw.readIntLittleEndian(intBuff, 0) & 0xFFFFFFFFL) + zip4jRaf.getStart());
        longBuff = getLongByteFromIntByte(intBuff);
        fileHeader.setOffsetLocalHeader((Raw.readLongLittleEndian(longBuff, 0) & 0xFFFFFFFFL));

        if (fileNameLength > 0) {
          byte[] fileNameBuf = new byte[fileNameLength];
          readIntoBuff(zip4jRaf, fileNameBuf);
          // Modified after user reported an issue http://www.lingala.net/zip4j/forum/index.php?topic=2.0
//					String fileName = new String(fileNameBuf, "Cp850"); 
          // Modified as per http://www.lingala.net/zip4j/forum/index.php?topic=41.0
//					String fileName = Zip4jUtil.getCp850EncodedString(fileNameBuf);

          String fileName = null;

          if (Zip4jUtil.isStringNotNullAndNotEmpty(zipModel.getFileNameCharset())) {
            fileName = new String(fileNameBuf, zipModel.getFileNameCharset());
          } else {
            fileName = Zip4jUtil.decodeFileName(fileNameBuf, fileHeader.isFileNameUTF8Encoded());
          }

          if (fileName == null) {
            throw new ZipException("fileName is null when reading central directory");
          }

          if (fileName.indexOf(":" + System.getProperty("file.separator")) >= 0) {
            fileName = fileName.substring(fileName.indexOf(":" + System.getProperty("file.separator")) + 2);
          }

          fileHeader.setFileName(fileName);
          fileHeader.setDirectory(fileName.endsWith("/") || fileName.endsWith("\\"));

        } else {
          fileHeader.setFileName(null);
        }

        //Extra field
        readAndSaveExtraDataRecord(zip4jRaf, fileHeader);

        //Read Zip64 Extra data records if exists
        readAndSaveZip64ExtendedInfo(fileHeader);

        //Read AES Extra Data record if exists
        readAndSaveAESExtraDataRecord(fileHeader);

//				if (fileHeader.isEncrypted()) {
//					
//					if (fileHeader.getEncryptionMethod() == ZipConstants.ENC_METHOD_AES) {
//						//Do nothing
//					} else {
//						if ((firstByte & 64) == 64) {
//							//hardcoded for now
//							fileHeader.setEncryptionMethod(1);
//						} else {
//							fileHeader.setEncryptionMethod(ZipConstants.ENC_METHOD_STANDARD);
//							fileHeader.setCompressedSize(fileHeader.getCompressedSize()
//									- ZipConstants.STD_DEC_HDR_SIZE);
//						}
//					}
//					
//				}

        if (fileCommentLength > 0) {
          byte[] fileCommentBuf = new byte[fileCommentLength];
          readIntoBuff(zip4jRaf, fileCommentBuf);
          fileHeader.setFileComment(new String(fileCommentBuf));
        }

        fileHeaderList.add(fileHeader);
      }
      centralDirectory.setFileHeaders(fileHeaderList);

      //Digital Signature
      DigitalSignature digitalSignature = new DigitalSignature();
      readIntoBuff(zip4jRaf, intBuff);
      int signature = Raw.readIntLittleEndian(intBuff, 0);
      if (signature != HeaderSignature.DIGITAL_SIGNATURE.getValue()) {
        return centralDirectory;
      }

      digitalSignature.setSignature(HeaderSignature.DIGITAL_SIGNATURE);

      //size of data
      readIntoBuff(zip4jRaf, shortBuff);
      int sizeOfData = Raw.readShortLittleEndian(shortBuff, 0);
      digitalSignature.setSizeOfData(sizeOfData);

      if (sizeOfData > 0) {
        byte[] sigDataBuf = new byte[sizeOfData];
        readIntoBuff(zip4jRaf, sigDataBuf);
        digitalSignature.setSignatureData(new String(sigDataBuf));
      }

      return centralDirectory;
    } catch (IOException e) {
      throw new ZipException(e);
    }
  }

  /**
   * Reads extra data record and saves it in the {@link FileHeader}
   *
   * @param fileHeader
   * @throws ZipException
   */
  private void readAndSaveExtraDataRecord(RandomAccessFile zip4jRaf, FileHeader fileHeader) throws ZipException {
    if (fileHeader == null) {
      throw new ZipException("file header is null");
    }

    int extraFieldLength = fileHeader.getExtraFieldLength();
    if (extraFieldLength <= 0) {
      return;
    }

    fileHeader.setExtraDataRecords(readExtraDataRecords(zip4jRaf, extraFieldLength));

  }

  /**
   * Reads extra data record and saves it in the {@link LocalFileHeader}
   *
   * @param localFileHeader
   * @throws ZipException
   */
  private void readAndSaveExtraDataRecord(RandomAccessFile zip4jRaf, LocalFileHeader localFileHeader) throws ZipException {
    if (localFileHeader == null) {
      throw new ZipException("file header is null");
    }

    int extraFieldLength = localFileHeader.getExtraFieldLength();
    if (extraFieldLength <= 0) {
      return;
    }

    localFileHeader.setExtraDataRecords(readExtraDataRecords(zip4jRaf, extraFieldLength));

  }

  /**
   * Reads extra data record and saves it in the {@link LocalFileHeader}
   *
   * @param localFileHeader
   * @throws ZipException
   */
  private void readAndSaveExtraDataRecord(InputStream inputStream, LocalFileHeader localFileHeader) throws ZipException {
    if (localFileHeader == null) {
      throw new ZipException("file header is null");
    }

    int extraFieldLength = localFileHeader.getExtraFieldLength();
    if (extraFieldLength <= 0) {
      return;
    }

    localFileHeader.setExtraDataRecords(readExtraDataRecords(inputStream, extraFieldLength));

  }

  /**
   * Reads extra data records
   *
   * @param extraFieldLength
   * @return ArrayList of {@link ExtraDataRecord}
   * @throws ZipException
   */
  private ArrayList readExtraDataRecords(RandomAccessFile zip4jRaf, int extraFieldLength) throws ZipException {

    if (extraFieldLength <= 0) {
      return null;
    }

    try {
      byte[] extraFieldBuf = new byte[extraFieldLength];
      zip4jRaf.read(extraFieldBuf);

      int counter = 0;
      ArrayList extraDataList = new ArrayList();
      while (counter < extraFieldLength) {
        ExtraDataRecord extraDataRecord = new ExtraDataRecord();
        int header = Raw.readShortLittleEndian(extraFieldBuf, counter);
        extraDataRecord.setSignature(HeaderSignature.EXTRA_DATA_RECORD);
        counter = counter + 2;
        int sizeOfRec = Raw.readShortLittleEndian(extraFieldBuf, counter);

        if ((2 + sizeOfRec) > extraFieldLength) {
          sizeOfRec = Raw.readShortBigEndian(extraFieldBuf, counter);
          if ((2 + sizeOfRec) > extraFieldLength) {
            //If this is the case, then extra data record is corrupt
            //skip reading any further extra data records
            break;
          }
        }

        extraDataRecord.setSizeOfData(sizeOfRec);
        counter = counter + 2;

        if (sizeOfRec > 0) {
          byte[] data = new byte[sizeOfRec];
          System.arraycopy(extraFieldBuf, counter, data, 0, sizeOfRec);
          extraDataRecord.setData(data);
        }
        counter = counter + sizeOfRec;
        extraDataList.add(extraDataRecord);
      }
      if (extraDataList.size() > 0) {
        return extraDataList;
      } else {
        return null;
      }
    } catch (IOException e) {
      throw new ZipException(e);
    }
  }

  /**
   * Reads extra data records
   *
   * @param extraFieldLength
   * @return ArrayList of {@link ExtraDataRecord}
   * @throws ZipException
   */
  private ArrayList readExtraDataRecords(InputStream inputStream, int extraFieldLength) throws ZipException {

    if (extraFieldLength <= 0) {
      return null;
    }

    try {
      byte[] extraFieldBuf = new byte[extraFieldLength];
      inputStream.read(extraFieldBuf);

      int counter = 0;
      ArrayList extraDataList = new ArrayList();
      while (counter < extraFieldLength) {
        ExtraDataRecord extraDataRecord = new ExtraDataRecord();
        int header = Raw.readShortLittleEndian(extraFieldBuf, counter);
        extraDataRecord.setHeader(header);
        counter = counter + 2;
        int sizeOfRec = Raw.readShortLittleEndian(extraFieldBuf, counter);

        if ((2 + sizeOfRec) > extraFieldLength) {
          sizeOfRec = Raw.readShortBigEndian(extraFieldBuf, counter);
          if ((2 + sizeOfRec) > extraFieldLength) {
            //If this is the case, then extra data record is corrupt
            //skip reading any further extra data records
            break;
          }
        }

        extraDataRecord.setSizeOfData(sizeOfRec);
        counter = counter + 2;

        if (sizeOfRec > 0) {
          byte[] data = new byte[sizeOfRec];
          System.arraycopy(extraFieldBuf, counter, data, 0, sizeOfRec);
          extraDataRecord.setData(data);
        }
        counter = counter + sizeOfRec;
        extraDataList.add(extraDataRecord);
      }
      if (extraDataList.size() > 0) {
        return extraDataList;
      } else {
        return null;
      }
    } catch (IOException e) {
      throw new ZipException(e);
    }
  }

  /**
   * Reads Zip64 End Of Central Directory Locator
   *
   * @return {@link Zip64EndOfCentralDirectoryLocator}
   * @throws ZipException
   */
  private Zip64EndOfCentralDirectoryLocator readZip64EndCentralDirLocator(RandomAccessFile zip4jRaf) throws ZipException {

    try {
      Zip64EndOfCentralDirectoryLocator zip64EndOfCentralDirectoryLocator = new Zip64EndOfCentralDirectoryLocator();

      setFilePointerToReadZip64EndCentralDirLoc(zip4jRaf);

      byte[] intBuff = new byte[4];
      byte[] longBuff = new byte[8];

      readIntoBuff(zip4jRaf, intBuff);
      int signature = Raw.readIntLittleEndian(intBuff, 0);
      if (signature == HeaderSignature.ZIP64_END_CENTRAL_DIRECTORY_LOCATOR.getValue()) {
        zipModel.setZip64Format(true);
        zip64EndOfCentralDirectoryLocator.setSignature(HeaderSignature.ZIP64_END_CENTRAL_DIRECTORY_LOCATOR);
      } else {
        zipModel.setZip64Format(false);
        return null;
      }

      readIntoBuff(zip4jRaf, intBuff);
      zip64EndOfCentralDirectoryLocator.setNoOfDiskStartOfZip64EndOfCentralDirRec(
          Raw.readIntLittleEndian(intBuff, 0));

      readIntoBuff(zip4jRaf, longBuff);
      zip64EndOfCentralDirectoryLocator.setOffsetZip64EndOfCentralDirRec(
          Raw.readLongLittleEndian(longBuff, 0));

      readIntoBuff(zip4jRaf, intBuff);
      zip64EndOfCentralDirectoryLocator.setTotNumberOfDiscs(Raw.readIntLittleEndian(intBuff, 0));

      return zip64EndOfCentralDirectoryLocator;

    } catch (Exception e) {
      throw new ZipException(e);
    }

  }

  /**
   * Reads Zip64 End of Central Directory Record
   *
   * @return {@link Zip64EndOfCentralDirectoryRecord}
   * @throws ZipException
   */
  private Zip64EndOfCentralDirectoryRecord readZip64EndCentralDirRec(RandomAccessFile zip4jRaf) throws ZipException {

    if (zipModel.getZip64EndOfCentralDirectoryLocator() == null) {
      throw new ZipException("invalid zip64 end of central directory locator");
    }

    long offSetStartOfZip64CentralDir =
        zipModel.getZip64EndOfCentralDirectoryLocator().getOffsetZip64EndOfCentralDirRec();

    if (offSetStartOfZip64CentralDir < 0) {
      throw new ZipException("invalid offset for start of end of central directory record");
    }

    try {
      zip4jRaf.seek(offSetStartOfZip64CentralDir);

      Zip64EndOfCentralDirectoryRecord zip64EndOfCentralDirectoryRecord = new Zip64EndOfCentralDirectoryRecord();

      byte[] shortBuff = new byte[2];
      byte[] intBuff = new byte[4];
      byte[] longBuff = new byte[8];

      //signature
      readIntoBuff(zip4jRaf, intBuff);
      int signature = Raw.readIntLittleEndian(intBuff, 0);
      if (signature != HeaderSignature.ZIP64_END_CENTRAL_DIRECTORY_RECORD.getValue()) {
        throw new ZipException("invalid signature for zip64 end of central directory record");
      }
      zip64EndOfCentralDirectoryRecord.setSignature(HeaderSignature.ZIP64_END_CENTRAL_DIRECTORY_RECORD);

      //size of zip64 end of central directory record
      readIntoBuff(zip4jRaf, longBuff);
      zip64EndOfCentralDirectoryRecord.setSizeOfZip64EndCentralDirRec(
          Raw.readLongLittleEndian(longBuff, 0));

      //version made by
      readIntoBuff(zip4jRaf, shortBuff);
      zip64EndOfCentralDirectoryRecord.setVersionMadeBy(Raw.readShortLittleEndian(shortBuff, 0));

      //version needed to extract
      readIntoBuff(zip4jRaf, shortBuff);
      zip64EndOfCentralDirectoryRecord.setVersionNeededToExtract(Raw.readShortLittleEndian(shortBuff, 0));

      //number of this disk
      readIntoBuff(zip4jRaf, intBuff);
      zip64EndOfCentralDirectoryRecord.setNoOfThisDisk(Raw.readIntLittleEndian(intBuff, 0));

      //number of the disk with the start of the central directory
      readIntoBuff(zip4jRaf, intBuff);
      zip64EndOfCentralDirectoryRecord.setNoOfThisDiskStartOfCentralDir(
          Raw.readIntLittleEndian(intBuff, 0));

      //total number of entries in the central directory on this disk
      readIntoBuff(zip4jRaf, longBuff);
      zip64EndOfCentralDirectoryRecord.setTotNoOfEntriesInCentralDirOnThisDisk(
          Raw.readLongLittleEndian(longBuff, 0));

      //total number of entries in the central directory
      readIntoBuff(zip4jRaf, longBuff);
      zip64EndOfCentralDirectoryRecord.setTotNoOfEntriesInCentralDir(
          Raw.readLongLittleEndian(longBuff, 0));

      //size of the central directory
      readIntoBuff(zip4jRaf, longBuff);
      zip64EndOfCentralDirectoryRecord.setSizeOfCentralDir(Raw.readLongLittleEndian(longBuff, 0));

      //offset of start of central directory with respect to the starting disk number
      readIntoBuff(zip4jRaf, longBuff);
      zip64EndOfCentralDirectoryRecord.setOffsetStartCenDirWRTStartDiskNo(
          Raw.readLongLittleEndian(longBuff, 0));

      //zip64 extensible data sector
      //44 is the size of fixed variables in this record
      long extDataSecSize = zip64EndOfCentralDirectoryRecord.getSizeOfZip64EndCentralDirRec() - 44;
      if (extDataSecSize > 0) {
        byte[] extDataSecRecBuf = new byte[(int) extDataSecSize];
        readIntoBuff(zip4jRaf, extDataSecRecBuf);
        zip64EndOfCentralDirectoryRecord.setExtensibleDataSector(extDataSecRecBuf);
      }

      return zip64EndOfCentralDirectoryRecord;

    } catch (IOException e) {
      throw new ZipException(e);
    }

  }

  /**
   * Reads Zip64 Extended info and saves it in the {@link FileHeader}
   *
   * @param fileHeader
   * @throws ZipException
   */
  private void readAndSaveZip64ExtendedInfo(FileHeader fileHeader) throws ZipException {
    if (fileHeader == null) {
      throw new ZipException("file header is null in reading Zip64 Extended Info");
    }

    if (fileHeader.getExtraDataRecords() == null || fileHeader.getExtraDataRecords().size() <= 0) {
      return;
    }

    Zip64ExtendedInfo zip64ExtendedInfo = readZip64ExtendedInfo(
        fileHeader.getExtraDataRecords(),
        fileHeader.getUncompressedSize(),
        fileHeader.getCompressedSize(),
        fileHeader.getOffsetLocalHeader(),
        fileHeader.getDiskNumberStart());

    if (zip64ExtendedInfo != null) {
      fileHeader.setZip64ExtendedInfo(zip64ExtendedInfo);
      if (zip64ExtendedInfo.getUncompressedSize() != -1)
        fileHeader.setUncompressedSize(zip64ExtendedInfo.getUncompressedSize());

      if (zip64ExtendedInfo.getCompressedSize() != -1)
        fileHeader.setCompressedSize(zip64ExtendedInfo.getCompressedSize());

      if (zip64ExtendedInfo.getOffsetLocalHeader() != -1)
        fileHeader.setOffsetLocalHeader(zip64ExtendedInfo.getOffsetLocalHeader());

      if (zip64ExtendedInfo.getDiskNumberStart() != -1)
        fileHeader.setDiskNumberStart(zip64ExtendedInfo.getDiskNumberStart());
    }
  }

  /**
   * Reads Zip64 Extended Info and saves it in the {@link LocalFileHeader}
   *
   * @param localFileHeader
   * @throws ZipException
   */
  private void readAndSaveZip64ExtendedInfo(LocalFileHeader localFileHeader) throws ZipException {
    if (localFileHeader == null) {
      throw new ZipException("file header is null in reading Zip64 Extended Info");
    }

    if (localFileHeader.getExtraDataRecords() == null || localFileHeader.getExtraDataRecords().size() <= 0) {
      return;
    }

    Zip64ExtendedInfo zip64ExtendedInfo = readZip64ExtendedInfo(
        localFileHeader.getExtraDataRecords(),
        localFileHeader.getUncompressedSize(),
        localFileHeader.getCompressedSize(),
        -1, -1);

    if (zip64ExtendedInfo != null) {
      localFileHeader.setZip64ExtendedInfo(zip64ExtendedInfo);

      if (zip64ExtendedInfo.getUncompressedSize() != -1)
        localFileHeader.setUncompressedSize(zip64ExtendedInfo.getUncompressedSize());

      if (zip64ExtendedInfo.getCompressedSize() != -1)
        localFileHeader.setCompressedSize(zip64ExtendedInfo.getCompressedSize());
    }
  }

  private Zip64ExtendedInfo readZip64ExtendedInfo(
      List<ExtraDataRecord> extraDataRecords,
      long unCompressedSize,
      long compressedSize,
      long offsetLocalHeader,
      int diskNumberStart) {

    for (ExtraDataRecord extraDataRecord : extraDataRecords) {
      if (extraDataRecord == null) {
        continue;
      }

      if (extraDataRecord.getHeader() == 0x0001) {

        Zip64ExtendedInfo zip64ExtendedInfo = new Zip64ExtendedInfo();

        byte[] byteBuff = extraDataRecord.getData();

        if (extraDataRecord.getSizeOfData() <= 0) {
          break;
        }
        byte[] longByteBuff = new byte[8];
        byte[] intByteBuff = new byte[4];
        int counter = 0;
        boolean valueAdded = false;

        if (((unCompressedSize & 0xFFFF) == 0xFFFF) && counter < extraDataRecord.getSizeOfData()) {
          System.arraycopy(byteBuff, counter, longByteBuff, 0, 8);
          long val = Raw.readLongLittleEndian(longByteBuff, 0);
          zip64ExtendedInfo.setUncompressedSize(val);
          counter += 8;
          valueAdded = true;
        }

        if (((compressedSize & 0xFFFF) == 0xFFFF) && counter < extraDataRecord.getSizeOfData()) {
          System.arraycopy(byteBuff, counter, longByteBuff, 0, 8);
          long val = Raw.readLongLittleEndian(longByteBuff, 0);
          zip64ExtendedInfo.setCompressedSize(val);
          counter += 8;
          valueAdded = true;
        }

        if (((offsetLocalHeader & 0xFFFF) == 0xFFFF) && counter < extraDataRecord.getSizeOfData()) {
          System.arraycopy(byteBuff, counter, longByteBuff, 0, 8);
          long val = Raw.readLongLittleEndian(longByteBuff, 0);
          zip64ExtendedInfo.setOffsetLocalHeader(val);
          counter += 8;
          valueAdded = true;
        }

        if (((diskNumberStart & 0xFFFF) == 0xFFFF) && counter < extraDataRecord.getSizeOfData()) {
          System.arraycopy(byteBuff, counter, intByteBuff, 0, 4);
          int val = Raw.readIntLittleEndian(intByteBuff, 0);
          zip64ExtendedInfo.setDiskNumberStart(val);
          counter += 8;
          valueAdded = true;
        }

        if (valueAdded) {
          return zip64ExtendedInfo;
        }

        break;
      }
    }
    return null;
  }

  /**
   * Sets the current random access file pointer at the start of signature
   * of the zip64 end of central directory record
   *
   * @throws ZipException
   */
  private void setFilePointerToReadZip64EndCentralDirLoc(RandomAccessFile zip4jRaf) throws ZipException {
    try {
      byte[] ebs = new byte[4];
      long pos = zip4jRaf.length() - ENDHDR;

      do {
        zip4jRaf.seek(pos--);
      } while (Raw.readLeInt(zip4jRaf, ebs) != HeaderSignature.END_OF_CENTRAL_DIRECTORY.getValue());

      // Now the file pointer is at the end of signature of Central Dir Rec
      // Seek back with the following values
      // 4 -> end of central dir signature
      // 4 -> total number of disks
      // 8 -> relative offset of the zip64 end of central directory record
      // 4 -> number of the disk with the start of the zip64 end of central directory
      // 4 -> zip64 end of central dir locator signature
      // Refer to Appnote for more information
      //TODO: Donot harcorde these values. Make use of ZipConstants
      zip4jRaf.seek(zip4jRaf.getFilePointer() - 4 - 4 - 8 - 4 - 4);
    } catch (IOException e) {
      throw new ZipException(e);
    }
  }

  /**
   * Reads local file header for the given file header
   *
   * @param fileHeader
   * @return {@link LocalFileHeader}
   * @throws ZipException
   */
  public LocalFileHeader readLocalFileHeader(RandomAccessFile zip4jRaf, FileHeader fileHeader) throws ZipException {
    if (fileHeader == null || zip4jRaf == null) {
      throw new ZipException("invalid read parameters for local header");
    }

    long locHdrOffset = fileHeader.getOffsetLocalHeader();

    if (fileHeader.getZip64ExtendedInfo() != null) {
      Zip64ExtendedInfo zip64ExtendedInfo = fileHeader.getZip64ExtendedInfo();
      if (zip64ExtendedInfo.getOffsetLocalHeader() > 0) {
        locHdrOffset = fileHeader.getOffsetLocalHeader();
      }
    }

    if (locHdrOffset < 0) {
      throw new ZipException("invalid local header offset");
    }

    try {
      zip4jRaf.seek(locHdrOffset);

      int length = 0;
      LocalFileHeader localFileHeader = new LocalFileHeader();

      byte[] shortBuff = new byte[2];
      byte[] intBuff = new byte[4];
      byte[] longBuff = new byte[8];

      //signature
      readIntoBuff(zip4jRaf, intBuff);
      int sig = Raw.readIntLittleEndian(intBuff, 0);
      if (sig != HeaderSignature.LOCAL_FILE_HEADER.getValue()) {
        throw new ZipException("invalid local header signature for file: " + fileHeader.getFileName());
      }
      localFileHeader.setSignature(HeaderSignature.LOCAL_FILE_HEADER);
      length += 4;

      //version needed to extract
      readIntoBuff(zip4jRaf, shortBuff);
      localFileHeader.setVersionNeededToExtract(Raw.readShortLittleEndian(shortBuff, 0));
      length += 2;

      //general purpose bit flag
      readIntoBuff(zip4jRaf, shortBuff);
      localFileHeader.setFileNameUTF8Encoded((Raw.readShortLittleEndian(shortBuff, 0) & UFT8_NAMES_FLAG) != 0);
      int firstByte = shortBuff[0];
      int result = firstByte & 1;
      if (result != 0) {
        localFileHeader.setEncrypted(true);
      }
      localFileHeader.setGeneralPurposeFlag(shortBuff);
      length += 2;

      //Check if data descriptor exists for local file header
      String binary = Integer.toBinaryString(firstByte);
      if (binary.length() >= 4)
        localFileHeader.setDataDescriptorExists(binary.charAt(3) == '1');

      //compression method
      readIntoBuff(zip4jRaf, shortBuff);
      int compressionTypeCode = Raw.readShortLittleEndian(shortBuff, 0);
      localFileHeader.setCompressionMethod(CompressionMethod.getCompressionMethodFromCode(compressionTypeCode));
      length += 2;

      //last mod file time
      readIntoBuff(zip4jRaf, intBuff);
      localFileHeader.setLastModifiedTime(Raw.readIntLittleEndian(intBuff, 0));
      length += 4;

      //crc-32
      readIntoBuff(zip4jRaf, intBuff);
      localFileHeader.setCrc32(Raw.readIntLittleEndian(intBuff, 0));
      localFileHeader.setCrcRawData((byte[]) intBuff.clone());
      length += 4;

      //compressed size
      readIntoBuff(zip4jRaf, intBuff);
      longBuff = getLongByteFromIntByte(intBuff);
      localFileHeader.setCompressedSize(Raw.readLongLittleEndian(longBuff, 0));
      length += 4;

      //uncompressed size
      readIntoBuff(zip4jRaf, intBuff);
      longBuff = getLongByteFromIntByte(intBuff);
      localFileHeader.setUncompressedSize(Raw.readLongLittleEndian(longBuff, 0));
      length += 4;

      //file name length
      readIntoBuff(zip4jRaf, shortBuff);
      int fileNameLength = Raw.readShortLittleEndian(shortBuff, 0);
      localFileHeader.setFileNameLength(fileNameLength);
      length += 2;

      //extra field length
      readIntoBuff(zip4jRaf, shortBuff);
      int extraFieldLength = Raw.readShortLittleEndian(shortBuff, 0);
      localFileHeader.setExtraFieldLength(extraFieldLength);
      length += 2;

      //file name
      if (fileNameLength > 0) {
        byte[] fileNameBuf = new byte[fileNameLength];
        readIntoBuff(zip4jRaf, fileNameBuf);
        // Modified after user reported an issue http://www.lingala.net/zip4j/forum/index.php?topic=2.0
//				String fileName = new String(fileNameBuf, "Cp850");
//				String fileName = Zip4jUtil.getCp850EncodedString(fileNameBuf);
        String fileName = Zip4jUtil.decodeFileName(fileNameBuf, localFileHeader.isFileNameUTF8Encoded());

        if (fileName == null) {
          throw new ZipException("file name is null, cannot assign file name to local file header");
        }

        if (fileName.indexOf(":" + System.getProperty("file.separator")) >= 0) {
          fileName = fileName.substring(fileName.indexOf(":" + System.getProperty("file.separator")) + 2);
        }

        localFileHeader.setFileName(fileName);
        length += fileNameLength;
      } else {
        localFileHeader.setFileName(null);
      }

      //extra field
      readAndSaveExtraDataRecord(zip4jRaf, localFileHeader);
      length += extraFieldLength;

      localFileHeader.setOffsetStartOfData(locHdrOffset + length);

      readAndSaveZip64ExtendedInfo(localFileHeader);

      readAndSaveAESExtraDataRecord(localFileHeader);

      if (localFileHeader.isEncrypted()) {

        if (localFileHeader.getEncryptionMethod() == EncryptionMethod.AES) {
          //Do nothing
        } else {
          if ((firstByte & 64) == 64) {
            //hardcoded for now
            localFileHeader.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD_VARIANT_STRONG);
          } else {
            localFileHeader.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
//						localFileHeader.setCompressedSize(localFileHeader.getCompressedSize()
//								- ZipConstants.STD_DEC_HDR_SIZE);
          }
        }

      }

      if (localFileHeader.getCrc32() <= 0) {
        localFileHeader.setCrc32(fileHeader.getCrc32());
        localFileHeader.setCrcRawData(fileHeader.getCrcRawData());
      }

      if (localFileHeader.getCompressedSize() <= 0) {
        localFileHeader.setCompressedSize(fileHeader.getCompressedSize());
      }

      if (localFileHeader.getUncompressedSize() <= 0) {
        localFileHeader.setUncompressedSize(fileHeader.getUncompressedSize());
      }

      return localFileHeader;
    } catch (IOException e) {
      throw new ZipException(e);
    }
  }

  public LocalFileHeader readLocalFileHeader(InputStream inputStream) throws IOException {
    try {
      int length = 0;
      LocalFileHeader localFileHeader = new LocalFileHeader();

      byte[] shortBuff = new byte[2];
      byte[] intBuff = new byte[4];
      byte[] longBuff = new byte[8];

      //signature
      int readLen = inputStream.read(intBuff);
      int sig = Raw.readIntLittleEndian(intBuff, 0);
      if (sig != HeaderSignature.LOCAL_FILE_HEADER.getValue()) {
        return null;
      }
      localFileHeader.setSignature(HeaderSignature.LOCAL_FILE_HEADER);
      length += 4;

      //version needed to extract
      inputStream.read(shortBuff);
      localFileHeader.setVersionNeededToExtract(Raw.readShortLittleEndian(shortBuff, 0));
      length += 2;

      //general purpose bit flag
      inputStream.read(shortBuff);
      localFileHeader.setFileNameUTF8Encoded((Raw.readShortLittleEndian(shortBuff, 0) & UFT8_NAMES_FLAG) != 0);
      int firstByte = shortBuff[0];
      int result = firstByte & 1;
      if (result != 0) {
        localFileHeader.setEncrypted(true);
      }
      localFileHeader.setGeneralPurposeFlag(shortBuff.clone());
      length += 2;

      //Check if data descriptor exists for local file header
      String binary = Integer.toBinaryString(firstByte);
      if (binary.length() >= 4)
        localFileHeader.setDataDescriptorExists(binary.charAt(3) == '1');

      //compression method
      inputStream.read(shortBuff);
      int compressionTypeCode = Raw.readShortLittleEndian(shortBuff, 0);
      localFileHeader.setCompressionMethod(CompressionMethod.getCompressionMethodFromCode(compressionTypeCode));
      length += 2;

      //last mod file time
      inputStream.read(intBuff);
      localFileHeader.setLastModifiedTime(Raw.readIntLittleEndian(intBuff, 0));
      length += 4;

      //crc-32
      inputStream.read(intBuff);
      localFileHeader.setCrc32(Raw.readIntLittleEndian(intBuff, 0));
      localFileHeader.setCrcRawData((byte[]) intBuff.clone());
      length += 4;

      //compressed size
      inputStream.read(intBuff);
      longBuff = getLongByteFromIntByte(intBuff);
      localFileHeader.setCompressedSize(Raw.readLongLittleEndian(longBuff, 0));
      length += 4;

      //uncompressed size
      inputStream.read(intBuff);
      longBuff = getLongByteFromIntByte(intBuff);
      localFileHeader.setUncompressedSize(Raw.readLongLittleEndian(longBuff, 0));
      length += 4;

      //file name length
      inputStream.read(shortBuff);
      int fileNameLength = Raw.readShortLittleEndian(shortBuff, 0);
      localFileHeader.setFileNameLength(fileNameLength);
      length += 2;

      //extra field length
      inputStream.read(shortBuff);
      int extraFieldLength = Raw.readShortLittleEndian(shortBuff, 0);
      localFileHeader.setExtraFieldLength(extraFieldLength);
      length += 2;

      //file name
      if (fileNameLength > 0) {
        byte[] fileNameBuf = new byte[fileNameLength];
        inputStream.read(fileNameBuf);
        // Modified after user reported an issue http://www.lingala.net/zip4j/forum/index.php?topic=2.0
//				String fileName = new String(fileNameBuf, "Cp850");
//				String fileName = Zip4jUtil.getCp850EncodedString(fileNameBuf);
        String fileName = Zip4jUtil.decodeFileName(fileNameBuf, localFileHeader.isFileNameUTF8Encoded());

        if (fileName == null) {
          throw new ZipException("file name is null, cannot assign file name to local file header");
        }

        if (fileName.indexOf(":" + System.getProperty("file.separator")) >= 0) {
          fileName = fileName.substring(fileName.indexOf(":" + System.getProperty("file.separator")) + 2);
        }

        localFileHeader.setFileName(fileName);
        length += fileNameLength;
      } else {
        localFileHeader.setFileName(null);
      }

      //extra field
      readAndSaveExtraDataRecord(inputStream, localFileHeader);
      length += extraFieldLength;

      readAndSaveZip64ExtendedInfo(localFileHeader);

      readAndSaveAESExtraDataRecord(localFileHeader);

      if (localFileHeader.isEncrypted()) {

        if (localFileHeader.getEncryptionMethod() == EncryptionMethod.AES) {
          //Do nothing
        } else {
          if ((firstByte & 64) == 64) {
            //hardcoded for now
            localFileHeader.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD_VARIANT_STRONG);
          } else {
            localFileHeader.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
//						localFileHeader.setCompressedSize(localFileHeader.getCompressedSize()
//								- ZipConstants.STD_DEC_HDR_SIZE);
          }
        }

      }

      return localFileHeader;
    } catch (ZipException e) {
      throw new IOException(e);
    }
  }

  public LocalFileHeader readExtendedLocalFileHeader(InputStream inputStream) throws IOException {
    try {
      LocalFileHeader localFileHeader = new LocalFileHeader();

      byte[] intBuff = new byte[4];
      byte[] longBuff = new byte[8];

      //signature
      int readLen = inputStream.read(intBuff);
      int sig = Raw.readIntLittleEndian(intBuff, 0);
      if (sig != HeaderSignature.EXTRA_DATA_RECORD.getValue()) {
        throw new ZipException("Extended local file header flag is set, but could not find signature");
      }
      localFileHeader.setSignature(HeaderSignature.EXTRA_DATA_RECORD);

      //crc-32
      inputStream.read(intBuff);
      localFileHeader.setCrc32(Raw.readIntLittleEndian(intBuff, 0));
      localFileHeader.setCrcRawData((byte[]) intBuff.clone());

      //compressed size
      inputStream.read(intBuff);
      longBuff = getLongByteFromIntByte(intBuff);
      localFileHeader.setCompressedSize(Raw.readLongLittleEndian(longBuff, 0));

      //uncompressed size
      inputStream.read(intBuff);
      longBuff = getLongByteFromIntByte(intBuff);
      localFileHeader.setUncompressedSize(Raw.readLongLittleEndian(longBuff, 0));

      return localFileHeader;
    } catch (ZipException e) {
      throw new IOException(e);
    }
  }

  /**
   * Reads AES Extra Data Record and saves it in the {@link FileHeader}
   *
   * @param fileHeader
   * @throws ZipException
   */
  private void readAndSaveAESExtraDataRecord(FileHeader fileHeader) throws ZipException {
    if (fileHeader == null) {
      throw new ZipException("file header is null in reading Zip64 Extended Info");
    }

    if (fileHeader.getExtraDataRecords() == null || fileHeader.getExtraDataRecords().size() <= 0) {
      return;
    }

    AESExtraDataRecord aesExtraDataRecord = readAESExtraDataRecord(fileHeader.getExtraDataRecords());
    if (aesExtraDataRecord != null) {
      fileHeader.setAesExtraDataRecord(aesExtraDataRecord);
      fileHeader.setEncryptionMethod(EncryptionMethod.AES);
    }
  }

  /**
   * Reads AES Extra Data Record and saves it in the {@link LocalFileHeader}
   *
   * @param localFileHeader
   * @throws ZipException
   */
  private void readAndSaveAESExtraDataRecord(LocalFileHeader localFileHeader) throws ZipException {
    if (localFileHeader == null) {
      throw new ZipException("file header is null in reading Zip64 Extended Info");
    }

    if (localFileHeader.getExtraDataRecords() == null || localFileHeader.getExtraDataRecords().size() <= 0) {
      return;
    }

    AESExtraDataRecord aesExtraDataRecord = readAESExtraDataRecord(localFileHeader.getExtraDataRecords());
    if (aesExtraDataRecord != null) {
      localFileHeader.setAesExtraDataRecord(aesExtraDataRecord);
      localFileHeader.setEncryptionMethod(EncryptionMethod.AES);
    }
  }

  /**
   * Reads AES Extra Data Record
   *
   * @param extraDataRecords
   * @return {@link AESExtraDataRecord}
   * @throws ZipException
   */
  private AESExtraDataRecord readAESExtraDataRecord(List<ExtraDataRecord> extraDataRecords) throws ZipException {

    if (extraDataRecords == null) {
      return null;
    }

    for (ExtraDataRecord extraDataRecord : extraDataRecords) {
      if (extraDataRecord == null) {
        continue;
      }

      if (extraDataRecord.getHeader() == HeaderSignature.AES_EXTRA_DATA_RECORD.getValue()) {

        if (extraDataRecord.getData() == null) {
          throw new ZipException("corrupt AES extra data records");
        }

        AESExtraDataRecord aesExtraDataRecord = new AESExtraDataRecord();

        aesExtraDataRecord.setSignature(HeaderSignature.AES_EXTRA_DATA_RECORD);
        aesExtraDataRecord.setDataSize(extraDataRecord.getSizeOfData());

        byte[] aesData = extraDataRecord.getData();
        aesExtraDataRecord.setVersionNumber(Raw.readShortLittleEndian(aesData, 0));
        byte[] vendorIDBytes = new byte[2];
        System.arraycopy(aesData, 2, vendorIDBytes, 0, 2);
        aesExtraDataRecord.setVendorID(new String(vendorIDBytes));
        aesExtraDataRecord.setAesKeyStrength(AesKeyStrength.getAesKeyStrengthFromRawCode(aesData[4] & 0xFF));
        aesExtraDataRecord.setCompressionMethod(
            CompressionMethod.getCompressionMethodFromCode(Raw.readShortLittleEndian(aesData, 5)));

        return aesExtraDataRecord;
      }
    }

    return null;
  }

  private byte[] readIntoBuff(RandomAccessFile zip4jRaf, byte[] buf) throws ZipException {
    try {
      if (zip4jRaf.read(buf, 0, buf.length) != -1) {
        return buf;
      } else {
        throw new ZipException("unexpected end of file when reading into buffer");
      }
    } catch (IOException e) {
      throw new ZipException("IOException when reading short buff", e);
    }
  }

  private byte[] getLongByteFromIntByte(byte[] intByte) {
    return new byte[] {intByte[0], intByte[1], intByte[2], intByte[3], 0, 0, 0, 0};
  }
}
