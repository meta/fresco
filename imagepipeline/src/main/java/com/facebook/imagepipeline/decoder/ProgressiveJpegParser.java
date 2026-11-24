/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.decoder;

import com.facebook.common.internal.Closeables;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Throwables;
import com.facebook.common.memory.ByteArrayPool;
import com.facebook.common.memory.PooledByteArrayBufferedInputStream;
import com.facebook.common.util.StreamUtil;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imageutils.JfifUtil;
import com.facebook.infer.annotation.Nullsafe;
import java.io.IOException;
import java.io.InputStream;

/**
 * Progressively scans jpeg data and instructs caller when enough data is available to decode a
 * partial image.
 *
 * <p>This class treats any sequence of bytes starting with 0xFFD8 as a valid jpeg image
 *
 * <p>Users should call parseMoreData method each time new chunk of data is received. The buffer
 * passed as a parameter should include entire image data received so far.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class ProgressiveJpegParser {

  /** Initial state of the parser. Next byte read by the parser should be 0xFF. */
  private static final int READ_FIRST_JPEG_BYTE = 0;

  /** Parser saw only one byte so far (0xFF). Next byte should be second byte of SOI marker */
  private static final int READ_SECOND_JPEG_BYTE = 1;

  /**
   * Next byte is either entropy coded data or first byte of a marker. First byte of marker cannot
   * appear in entropy coded data, unless it is followed by 0x00 escape byte.
   */
  private static final int READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA = 2;

  /**
   * Last read byte is 0xFF, possible start of marker (possible, because next byte might be "escape
   * byte" or 0xFF again)
   */
  private static final int READ_MARKER_SECOND_BYTE = 3;

  /**
   * Last two bytes constitute a marker that indicates start of a segment, the following two bytes
   * denote 16bit size of the segment
   */
  private static final int READ_SIZE_FIRST_BYTE = 4;

  /**
   * Last three bytes are marker and first byte of segment size, after reading next byte, bytes
   * constituting remaining part of segment will be skipped
   */
  private static final int READ_SIZE_SECOND_BYTE = 5;

  /** Parsed data is not a JPEG file */
  private static final int NOT_A_JPEG = 6;

  /** The buffer size in bytes to use. */
  private static final int BUFFER_SIZE = 16 * 1024;

  private int mParserState;
  private int mLastByteRead;

  /** number of bytes consumed so far */
  private int mBytesParsed;

  /** number of next fully parsed scan after reaching next SOS or EOI markers */
  private int mNextFullScanNumber;

  private int mBestScanNumber;
  private int mBestScanEndOffset;
  private boolean mEndMarkerRead;

  private final ByteArrayPool mByteArrayPool;

  public ProgressiveJpegParser(ByteArrayPool byteArrayPool) {
    mByteArrayPool = Preconditions.checkNotNull(byteArrayPool);
    mBytesParsed = 0;
    mLastByteRead = 0;
    mNextFullScanNumber = 0;
    mBestScanEndOffset = 0;
    mBestScanNumber = 0;
    mParserState = READ_FIRST_JPEG_BYTE;
  }

  /**
   * If this is the first time calling this method, the buffer will be checked to make sure it
   * starts with SOI marker (0xffd8). If the image has been identified as a non-JPEG, data will be
   * ignored and false will be returned immediately on all subsequent calls.
   *
   * <p>This object maintains state of the position of the last read byte. On repeated calls to this
   * method, it will continue from where it left off.
   *
   * @param encodedImage Next set of bytes received by the caller
   * @return true if a new full scan has been found
   */
  public boolean parseMoreData(final EncodedImage encodedImage) {
    if (mParserState == NOT_A_JPEG) {
      return false;
    }

    final int dataBufferSize = encodedImage.getSize();

    // Is there any new data to parse?
    // mBytesParsed might be greater than size of dataBuffer - that happens when
    // we skip more data than is available to read inside doParseMoreData method
    if (dataBufferSize <= mBytesParsed) {
      return false;
    }

    final InputStream bufferedDataStream =
        new PooledByteArrayBufferedInputStream(
            encodedImage.getInputStreamOrThrow(), mByteArrayPool.get(BUFFER_SIZE), mByteArrayPool);
    try {
      StreamUtil.skip(bufferedDataStream, mBytesParsed);
      return doParseMoreData(bufferedDataStream);
    } catch (IOException ioe) {
      // Does not happen - streams returned by PooledByteBuffers do not throw IOExceptions
      Throwables.propagate(ioe);
      return false;
    } finally {
      Closeables.closeQuietly(bufferedDataStream);
    }
  }

  /**
   * Parses more data from inputStream.
   *
   * @param inputStream instance of buffered pooled byte buffer input stream
   */
  private boolean doParseMoreData(final InputStream inputStream) {
    final int oldBestScanNumber = mBestScanNumber;
    try {
      int nextByte;
      while (mParserState != NOT_A_JPEG && (nextByte = inputStream.read()) != -1) {
        mBytesParsed++;
        if (mEndMarkerRead) {
          // There should be no more data after the EOI marker, just in case there is lets
          // bail out instead of trying to parse the unknown data
          mParserState = NOT_A_JPEG;
          mEndMarkerRead = false;
          return false;
        }
        switch (mParserState) {
          case READ_FIRST_JPEG_BYTE:
            if (nextByte == JfifUtil.MARKER_FIRST_BYTE) {
              mParserState = READ_SECOND_JPEG_BYTE;
            } else {
              mParserState = NOT_A_JPEG;
            }
            break;

          case READ_SECOND_JPEG_BYTE:
            if (nextByte == JfifUtil.MARKER_SOI) {
              mParserState = READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA;
            } else {
              mParserState = NOT_A_JPEG;
            }
            break;

          case READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA:
            if (nextByte == JfifUtil.MARKER_FIRST_BYTE) {
              mParserState = READ_MARKER_SECOND_BYTE;
            }
            break;

          case READ_MARKER_SECOND_BYTE:
            if (nextByte == JfifUtil.MARKER_FIRST_BYTE) {
              mParserState = READ_MARKER_SECOND_BYTE;
            } else if (nextByte == JfifUtil.MARKER_ESCAPE_BYTE) {
              mParserState = READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA;
            } else if (nextByte == JfifUtil.MARKER_EOI) {
              mEndMarkerRead = true;
              newScanOrImageEndFound(mBytesParsed - 2);
              // There should be no data after the EOI marker, but in case there is, let's process
              // the next byte as a first marker byte.
              mParserState = READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA;
            } else {
              if (nextByte == JfifUtil.MARKER_SOS) {
                newScanOrImageEndFound(mBytesParsed - 2);
              }

              if (doesMarkerStartSegment(nextByte)) {
                mParserState = READ_SIZE_FIRST_BYTE;
              } else {
                mParserState = READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA;
              }
            }
            break;

          case READ_SIZE_FIRST_BYTE:
            mParserState = READ_SIZE_SECOND_BYTE;
            break;

          case READ_SIZE_SECOND_BYTE:
            final int size = (mLastByteRead << 8) + nextByte;
            if (size < 2) {
              mParserState = NOT_A_JPEG;
              return false;
            }
            // We need to jump after the end of the segment - skip size-2 next bytes.
            // We might want to skip more data than is available to read, in which case we will
            // consume entire data in inputStream and exit this function before entering another
            // iteration of the loop.
            final int bytesToSkip = size - 2;
            StreamUtil.skip(inputStream, bytesToSkip);
            mBytesParsed += bytesToSkip;
            mParserState = READ_MARKER_FIRST_BYTE_OR_ENTROPY_DATA;
            break;

          case NOT_A_JPEG:
          default:
            Preconditions.checkState(false);
        }

        mLastByteRead = nextByte;
      }
    } catch (IOException ioe) {
      // does not happen, input stream returned by pooled byte buffer does not throw IOExceptions
      Throwables.propagate(ioe);
    }
    return mParserState != NOT_A_JPEG && mBestScanNumber != oldBestScanNumber;
  }

  /** Not every marker is followed by associated segment */
  private static boolean doesMarkerStartSegment(int markerSecondByte) {
    if (markerSecondByte == JfifUtil.MARKER_TEM) {
      return false;
    }

    if (markerSecondByte >= JfifUtil.MARKER_RST0 && markerSecondByte <= JfifUtil.MARKER_RST7) {
      return false;
    }

    return markerSecondByte != JfifUtil.MARKER_EOI && markerSecondByte != JfifUtil.MARKER_SOI;
  }

  private void newScanOrImageEndFound(int offset) {
    if (mNextFullScanNumber > 0) {
      mBestScanEndOffset = offset;
    }
    mBestScanNumber = mNextFullScanNumber++;
  }

  public boolean isJpeg() {
    return mBytesParsed > 1 && mParserState != NOT_A_JPEG;
  }

  /**
   * @return offset at which parsed data should be cut to decode best available partial result
   */
  public int getBestScanEndOffset() {
    return mBestScanEndOffset;
  }

  /**
   * @return number of the best scan found so far
   */
  public int getBestScanNumber() {
    return mBestScanNumber;
  }

  /** Returns true if the end marker has been read. */
  public boolean isEndMarkerRead() {
    return mEndMarkerRead;
  }
}
