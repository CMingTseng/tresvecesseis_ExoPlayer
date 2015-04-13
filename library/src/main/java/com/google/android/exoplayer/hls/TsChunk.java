/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.hls;

import com.google.android.exoplayer.extractor.DefaultExtractorInput;
import com.google.android.exoplayer.extractor.Extractor;
import com.google.android.exoplayer.extractor.ExtractorInput;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;

import java.io.IOException;

/**
 * A MPEG2TS chunk.
 */
public final class TsChunk extends HlsChunk {

  /**
   * The index of the variant in the master playlist.
   */
  public final int variantIndex;
  /**
   * The start time of the media contained by the chunk.
   */
  public final long startTimeUs;
  /**
   * The end time of the media contained by the chunk.
   */
  public final long endTimeUs;
  /**
   * The chunk index.
   */
  public final int chunkIndex;
  /**
   * True if this is the last chunk in the media. False otherwise.
   */
  public final boolean isLastChunk;
  /**
   * The extractor into which this chunk is being consumed.
   */
  public final HlsExtractorWrapper extractor;

  private int loadPosition;
  private volatile boolean loadFinished;
  private volatile boolean loadCanceled;

  /**
   * @param dataSource A {@link DataSource} for loading the data.
   * @param dataSpec Defines the data to be loaded.
   * @param extractor An extractor to parse samples from the data.
   * @param variantIndex The index of the variant in the master playlist.
   * @param startTimeUs The start time of the media contained by the chunk, in microseconds.
   * @param endTimeUs The end time of the media contained by the chunk, in microseconds.
   * @param chunkIndex The index of the chunk.
   * @param isLastChunk True if this is the last chunk in the media. False otherwise.
   */
  public TsChunk(DataSource dataSource, DataSpec dataSpec, HlsExtractorWrapper extractor,
      int variantIndex, long startTimeUs, long endTimeUs, int chunkIndex, boolean isLastChunk) {
    super(dataSource, dataSpec);
    this.extractor = extractor;
    this.variantIndex = variantIndex;
    this.startTimeUs = startTimeUs;
    this.endTimeUs = endTimeUs;
    this.chunkIndex = chunkIndex;
    this.isLastChunk = isLastChunk;
  }

  @Override
  public void consume() throws IOException {
    // Do nothing.
  }

  @Override
  public boolean isLoadFinished() {
    return loadFinished;
  }

  // Loadable implementation

  @Override
  public void cancelLoad() {
    loadCanceled = true;
  }

  @Override
  public boolean isLoadCanceled() {
    return loadCanceled;
  }

  @Override
  public void load() throws IOException, InterruptedException {
    ExtractorInput input;
    try {
      input = new DefaultExtractorInput(dataSource, 0, dataSource.open(dataSpec));
      // If we previously fed part of this chunk to the extractor, skip it this time.
      // TODO: Ideally we'd construct a dataSpec that only loads the remainder of the data here,
      // rather than loading the whole chunk again and then skipping data we previously loaded. To
      // do this is straightforward for non-encrypted content, but more complicated for content
      // encrypted with AES, for which we'll need to modify the way that decryption is performed.
      input.skipFully(loadPosition);
      try {
        int result = Extractor.RESULT_CONTINUE;
        while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
          result = extractor.read(input);
        }
      } finally {
        loadPosition = (int) input.getPosition();
      }
    } finally {
      dataSource.close();
    }
  }

}
